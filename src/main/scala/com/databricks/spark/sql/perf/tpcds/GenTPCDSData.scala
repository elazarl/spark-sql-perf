/*
 * Copyright 2015 Databricks Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.spark.sql.perf.tpcds

import org.apache.spark.sql.SparkSession

case class GenTPCDSDataConfig(
    master: String = "local[*]",
    dsdgenDir: String = "/usr/local/bin",
    scaleFactor: String = null,
    location: String = null,
    format: String = null,
    useDoubleForDecimal: Boolean = false,
    useStringForDate: Boolean = false,
    overwrite: Boolean = false,
    partitionTables: Boolean = true,
    clusterByPartitionColumns: Boolean = true,
    filterOutNullPartitionValues: Boolean = true,
    numPartitions: Int = 10000)

/**
 * Gen TPCDS data.
 * To run this:
 * {{{
 *   build/sbt "test:runMain <this class> -d <dsdgenDir> -s <scaleFactor> -l <location> -f <format>"
 * }}}
 */
object GenTPCDSData {
  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[GenTPCDSDataConfig]("Gen-TPC-DS-data") {
      opt[String]('m', "master")
        .action { (x, c) => c.copy(master = x) }
        .text("the Spark master to use, default to local[*]")
      opt[String]('d', "dsdgenDir")
        .action { (x, c) => c.copy(dsdgenDir = x) }
        .text("location of dsdgen")
        .required()
      opt[String]('s', "scaleFactor")
        .action((x, c) => c.copy(scaleFactor = x))
        .text("scaleFactor defines the size of the dataset to generate (in GB)")
      opt[String]('l', "location")
        .action((x, c) => c.copy(location = x))
        .text("root directory of location to create data in")
      opt[String]('f', "format")
        .action((x, c) => c.copy(format = x))
        .text("valid spark format, Parquet, ORC ...")
      opt[Boolean]('i', "useDoubleForDecimal")
        .action((x, c) => c.copy(useDoubleForDecimal = x))
        .text("true to replace DecimalType with DoubleType")
      opt[Boolean]('e', "useStringForDate")
        .action((x, c) => c.copy(useStringForDate = x))
        .text("true to replace DateType with StringType")
      opt[Boolean]('o', "overwrite")
        .action((x, c) => c.copy(overwrite = x))
        .text("overwrite the data that is already there")
      opt[Boolean]('p', "partitionTables")
        .action((x, c) => c.copy(partitionTables = x))
        .text("create the partitioned fact tables")
      opt[Boolean]('c', "clusterByPartitionColumns")
        .action((x, c) => c.copy(clusterByPartitionColumns = x))
        .text("shuffle to get partitions coalesced into single files")
      opt[Boolean]('v', "filterOutNullPartitionValues")
        .action((x, c) => c.copy(filterOutNullPartitionValues = x))
        .text("true to filter out the partition with NULL key value")
      opt[Int]('n', "numPartitions")
        .action((x, c) => c.copy(numPartitions = x))
        .text("how many dsdgen partitions to run - number of input tasks.")
      help("help")
        .text("prints this usage text")
    }

    parser.parse(args, GenTPCDSDataConfig()) match {
      case Some(config) =>
        run(config)
      case None =>
        System.exit(1)
    }
  }

  private def run(config: GenTPCDSDataConfig) {
    val spark = SparkSession
      .builder()
      .appName(getClass.getName)
      .master(config.master)
      .getOrCreate()
    val sc = spark.sparkContext;
    val sqlContext = spark.sqlContext

    val tables = new TPCDSTables(spark.sqlContext,
      dsdgenDir = config.dsdgenDir,
      scaleFactor = config.scaleFactor,
      useDoubleForDecimal = config.useDoubleForDecimal,
      useStringForDate = config.useStringForDate)

    val nonPartitionedTables = Array("call_center", "catalog_page", "customer", "customer_address",
                                     "customer_demographics", "date_dim", "household_demographics",
                                     "income_band", "item", "promotion", "reason", "ship_mode",
                                     "store",  "time_dim", "warehouse", "web_page", "web_site");
    val partitionedTables = Array("inventory", "web_returns", "catalog_returns", "store_returns", "web_sales", "catalog_sales", "store_sales")
    tables.genData(
      location = config.location,
      format = config.format,
      overwrite = config.overwrite,
      partitionTables = config.partitionTables,
      clusterByPartitionColumns = config.clusterByPartitionColumns,
      filterOutNullPartitionValues = config.filterOutNullPartitionValues,
      tableFilter = config.tableFilter,
      numPartitions = config.numPartitions)

    val databaseName = s"tpcds_sf${config.scaleFactor}" +
      s"""_${if (config.useDoubleForDecimal) "with" else "no"}decimal""" +
      s"""_${if (config.useStringForDate) "with" else "no"}date""" +
      s"""_${if (config.filterOutNullPartitionValues) "no" else "with"}nulls"""
    sqlContext.sql(s"drop database if exists $databaseName cascade")
    sqlContext.sql(s"create database $databaseName")
    tables.createExternalTables(config.location, config.format, databaseName, overwrite = true, discoverPartitions = true)
    tables.analyzeTables(databaseName, analyzeColumns = true)
  }

}
