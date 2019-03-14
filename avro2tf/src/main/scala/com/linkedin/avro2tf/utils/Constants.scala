package com.linkedin.avro2tf.utils

/**
 * Constants used by TensorizeIn
 *
 */
object Constants {

  final val AVRO_NAME_SPACE = "topLevelRecord"
  final val AVRO_RECORD = "avro"
  final val BLACKLIST = "blacklist"
  final val COLUMN_NAME = "columnName"
  final val DEFAULT_TOKENIZER_DELIMITER_REGEX: String = "\\W"
  final val DISABLE_DELETE_SOURCE = false
  final val EMPTY_AVRO_NAME_SPACE = ""
  final val EMPTY_STRING = ""
  final val ENABLE_HDFS_OVERWRITE = true
  final val ENABLE_PRETTY_PRINT_JSON = true
  final val ENABLE_RECURSIVE = true
  final val FEATURE_LIST_DIR_NAME = "featureList"
  final val FILE_NAME_REGEX = "part*"
  final val HASH_INFO = "hashInfo"
  final val HASH_INFO_HASH_BUCKET_SIZE = "hashBucketSize"
  final val HASH_INFO_NUM_HASH_FUNCTIONS = "numHashFunctions"
  final val HASH_INFO_COMBINER_TYPE = "combinerType"
  final val INDICES = "indices"
  final val MAX = "max"
  final val METADATA_DIR_NAME = "metadata"
  final val NEW_LINE_CHARACTER = "\n"
  final val NTV_NAME = "name"
  final val NTV_TERM = "term"
  final val NTV_VALUE = "value"
  final val NTV_VALUE_TEXT_FEATURE = 1f
  final val REGEXED_COLUMN_NAME_SUFFIX = "regexed"
  final val REMOVE_STOP_WORDS = "removeStopWords"
  final val REMOVER_COLUMN_NAME_SUFFIX = "remover"
  final val SCHEMA_FILE_NAME = "schemaOutputFile"
  final val SPLIT_REGEX = ","
  final val TF_RECORD = "tfrecord"
  final val TMP_FEATURE_LIST = "tmpFeatureList"
  final val TENSOR_METADATA_FILE_NAME = "tensor_metadata.json"
  final val TEST_DATA_DIR_NAME = "testData"
  final val TOKENIZATION_CONFIG = "tokenization"
  final val TRAINING_DATA_DIR_NAME = "trainingData"
  final val VALUES = "values"
  final val WHITELIST = "whitelist"
  final val WILD_CARD = "*"
}