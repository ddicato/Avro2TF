package com.linkedin.avro2tf.jobs

import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable
import scala.io.Source

import com.linkedin.avro2tf.configs.DataType
import com.linkedin.avro2tf.helpers.Avro2TFConfigHelper
import com.linkedin.avro2tf.parsers.Avro2TFParams
import com.linkedin.avro2tf.utils.CommonUtils
import com.linkedin.avro2tf.constants.Constants._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.{Column, DataFrame, Row}
import org.slf4j.{Logger, LoggerFactory}

object FeatureIndicesConversion {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  /**
   * The main function to perform Feature indices conversion job.
   *
   * Note: for any null columns, we will pad an entry with the max or last index
   *
   * @param dataFrame Input data Spark DataFrame
   * @param params Avro2TF parameters specified by user
   * @return A Spark DataFrame
   */
  def run(dataFrame: DataFrame, params: Avro2TFParams): DataFrame = {

    val columnFeatureMapping = loadColumnFeatureList(params, dataFrame.sparkSession.sparkContext.hadoopConfiguration)
    val outputTensorDataTypes = Avro2TFConfigHelper.getOutputTensorDataTypes(params)
    val outputTensorSparsity = Avro2TFConfigHelper.getOutputTensorSparsity(params)
    val dataFrameSchema = dataFrame.schema
    val convertedColumns = new mutable.ArrayBuffer[Column]
    val convertedColumnNames = new mutable.HashSet[String]

    columnFeatureMapping.foreach {
      case (columnName, featureMapping) =>
        if (CommonUtils.isArrayOfString(dataFrameSchema(columnName).dataType) ||
          dataFrameSchema(columnName).dataType.isInstanceOf[StringType]) {
          if (outputTensorDataTypes(columnName) == DataType.int || outputTensorDataTypes(columnName) == DataType.long) {
            if (dataFrameSchema(columnName).dataType.isInstanceOf[StringType]) {
              convertedColumns
                .append(
                  convertStringToId(featureMapping, params.discardUnknownEntries)(dataFrame(columnName))
                    .name(columnName))
            } else {
              convertedColumns
                .append(
                  convertStringSeqToIdSeq(featureMapping, params.discardUnknownEntries)(dataFrame(columnName))
                    .name(columnName))
            }
            convertedColumnNames.add(columnName)
          } else {
            logger.warn(
              s"Feature list: $columnName is not used, " +
                s"because indices in $columnName does not need to be converted, " +
                s"according to your specified type: ${outputTensorDataTypes(columnName)} in outputTensorInfo"
            )
          }
        } else if (CommonUtils.isArrayOfNTV(dataFrameSchema(columnName).dataType)) {
          if (outputTensorSparsity(columnName)) {
            convertedColumns.append(
              convertNTVToSparseVector(featureMapping, params.discardUnknownEntries, params.enableFilterZero)(dataFrame(columnName))
                .name(columnName))
          } else {
            convertedColumns.append(
              convertNTVToDenseVector(featureMapping, params.discardUnknownEntries)(dataFrame(columnName))
                .name(columnName))
          }
          convertedColumnNames.add(columnName)
        } else {
          throw new IllegalArgumentException(s"Data type of column: $columnName is not supported")
        }
    }

    val oldColumns = dataFrame.columns.filter(colName => !convertedColumnNames.contains(colName)).map(dataFrame(_))
    dataFrame.select(oldColumns ++ convertedColumns: _*)
  }

  /**
   * Load featureList to form mapping info
   *
   * @param params Parameters specified by user
   * @param hadoopConf Hadoop configuration
   */
  private def loadColumnFeatureList(
    params: Avro2TFParams,
    hadoopConf: Configuration
  ): Map[String, Map[String, Long]] = {

    val columnFeatureMapping = new mutable.HashMap[String, Map[String, Long]]
    val fs = FileSystem.get(hadoopConf)
    val featureListFiles = fs.listFiles(new Path(params.workingDir.featureListPath), ENABLE_RECURSIVE)

    while (featureListFiles.hasNext) {
      val sourcePath = featureListFiles.next().getPath
      val columnName = sourcePath.getName
      val inputStream = fs.open(new Path(s"${params.workingDir.featureListPath}/$columnName"))

      columnFeatureMapping(columnName) = Source.fromInputStream(inputStream, UTF_8.name())
        .getLines()
        .zipWithIndex
        .map(x => x._1 -> x._2.toLong)
        .toMap
      inputStream.close()
    }

    fs.close()
    columnFeatureMapping.toMap
  }

  /**
   * Spark UDF function to convert a column of NTVs to a column of SparseVector
   *
   * @param featureMapping The mapping of name+term to id
   * @param discardUnknownEntries Whether to discard unknown entries
   * @param filterZeros If it's set true, the constructed sparse vector doesn't have zeros in values
   * @return A Spark udf
   */
  private def convertNTVToSparseVector(
    featureMapping: Map[String, Long],
    discardUnknownEntries: Boolean,
    filterZeros: Boolean): UserDefinedFunction = {

    udf {
      ntvs: Seq[Row] => {
        val idValues = convertNTVToIdValues(ntvs, featureMapping, discardUnknownEntries)
        Avro2TF.SparseVector(idValues.map(_.id), idValues.map(_.value), filterZeros)
      }
    }
  }

  /**
   * Spark UDF function to convert a column of NTVs to a column of dense vector
   *
   * @param featureMapping The mapping of name+term to id
   * @return A Spark udf
   */
  private def convertNTVToDenseVector(
    featureMapping: Map[String, Long],
    discardUnknownEntries: Boolean): UserDefinedFunction = {

    udf {
      ntvs: Seq[Row] => {
        val idValues = convertNTVToIdValues(ntvs, featureMapping, discardUnknownEntries)
        val numUniqueValues = if (discardUnknownEntries) featureMapping.size else featureMapping.size + 1
        CommonUtils.idValuesToDense(idValues, numUniqueValues)
      }
    }
  }

  /**
   *
   * @param ntvs A seq of NTV in Row type
   * @param featureMapping The mapping of name+term to id
   * @param discardUnknownEntries Whether to discard unknown entries
   * @return A seq of IdValue
   */
  private def convertNTVToIdValues(
    ntvs: Seq[Row],
    featureMapping: Map[String, Long],
    discardUnknownEntries: Boolean): Seq[Avro2TF.IdValue] = {

    // The number of unique name-term combinations
    val numUniqueValues = featureMapping.size.toLong
    val lastIndex = if (discardUnknownEntries) numUniqueValues - 1 else numUniqueValues
    val paddingEntry = Avro2TF.IdValue(lastIndex, 0)
    if (ntvs == null || ntvs.isEmpty) {
      // if bag is empty put a dummy one with id as the unknown Id (last id) = numUniqueValues
      Seq(paddingEntry)
    } else {
      val idValuesBuffer = new mutable.ArrayBuffer[Avro2TF.IdValue]
      val unknownIdValue = Avro2TF.IdValue(lastIndex, 1)
      var hasUnknownId = false

      ntvs.foreach {
        ntv => {
          val name = ntv.getAs[String](NTV_NAME)
          val term = ntv.getAs[String](NTV_TERM)
          val value = CommonUtils.convertValueOfNTVToFloat(ntv)
          val id = featureMapping.getOrElse(s"$name,$term", lastIndex)

          if (featureMapping.contains(s"$name,$term")) {
            idValuesBuffer.append(Avro2TF.IdValue(id, value))
          } else {
            hasUnknownId = true
          }
        }
      }

      // if multiple name+term are mapped to unknown id, we only keep one of them with value 1.0
      if (!discardUnknownEntries && hasUnknownId) {
        idValuesBuffer.append(unknownIdValue)
      }
      if (idValuesBuffer.isEmpty) {
        idValuesBuffer.append(paddingEntry)
      }
      idValuesBuffer
    }
  }

  /**
   * Spark UDF function to convert a column of String Seq to a column of Id Seq
   *
   * @param featureMapping The mapping of word to id
   * @param discardUnknownEntries Whether to discard unknown entries
   * @return A Spark udf
   */
  private def convertStringSeqToIdSeq(
    featureMapping: Map[String, Long],
    discardUnknownEntries: Boolean): UserDefinedFunction = {

    udf {
      stringSeq: Seq[String] => {
        val lastIndex = if (discardUnknownEntries) featureMapping.size.toLong - 1 else featureMapping.size.toLong

        if (stringSeq == null || stringSeq.isEmpty) {
          Seq(lastIndex)
        } else {
          val ids = stringSeq.filter(x => discardUnknownEntries && !featureMapping.contains(x))
            .map(word => featureMapping.getOrElse(word, lastIndex))
          if (ids.isEmpty) {
            Seq(lastIndex)
          } else {
            ids
          }
        }
      }
    }
  }

  /**
   * Spark UDF function to map a String to an Id
   *
   * @param featureMapping The mapping of word to id
   * @param discardUnknownEntries Whether to discard unknown entries
   * @return A Spark udf
   */
  private def convertStringToId(
    featureMapping: Map[String, Long],
    discardUnknownEntries: Boolean): UserDefinedFunction = {

    udf {
      stringValue: String => {
        val lastIndex = if (discardUnknownEntries) featureMapping.size.toLong - 1 else featureMapping.size.toLong

        if (stringValue == null) {
          lastIndex
        } else {
          featureMapping.getOrElse(stringValue, lastIndex)
        }
      }
    }
  }
}
