/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.example

import com.google.cloud.RetryOption
import com.google.cloud.bigquery.JobInfo.{CreateDisposition, WriteDisposition}
import com.google.cloud.bigquery.QueryJobConfiguration.Priority
import com.google.cloud.bigquery._
import org.apache.spark.sql.types.{DateType, StructType}
import org.threeten.bp.Duration

import scala.util.{Failure, Success}

object NativeTableManager {
  def getExistingPartitions(tableId: TableId, bigQuery: BigQuery): TableResult = {
    val tableSpec = tableId.getProject + ":" + tableId.getDataset + "." + tableId.getTable
    tableId.toString + "$__PARTITIONS_SUMMARY__"
    bigQuery.query(QueryJobConfiguration.newBuilder(
      s"""SELECT
         |  partition_id,
         |  TIMESTAMP(creation_time/1000) AS creation_time
         |FROM [$tableSpec]""".stripMargin)
      .setUseLegacySql(true)
      .setPriority(Priority.INTERACTIVE)
      .build())
  }

  def createTableIfNotExists(project: String, dataset: String, table: String, c: Config, schema: StructType, bigquery: BigQuery, expirationMs: scala.Option[Long] = None): Boolean = {
    val tableId = TableId.of(project, dataset, table)
    createTableIfNotExistsWithId(c, schema, tableId, bigquery, expirationMs)
  }

  def createTableIfNotExistsWithId(c: Config, schema: StructType, tableId: TableId, bigquery: BigQuery, expirationMs: scala.Option[Long] = None): Boolean = {
    if (!ExternalTableManager.tableExists(tableId, bigquery)) {
      createTable(c, schema, tableId, bigquery, expirationMs)
      true
    } else false
  }

  def copyOnto(srcProject: String, srcDataset: String, srcTable: String, destProject: String, destDataset: String, destTable: String, destPartition: scala.Option[String] = None, bq: BigQuery): scala.util.Try[Job] = {
    val tableWithPartition = destPartition.map(partId => destTable + "$" + partId).getOrElse(destTable)

    val srcTableId = TableId.of(srcProject, srcDataset, srcTable)
    val destTableId = TableId.of(destProject, destDataset, tableWithPartition)

    val job = copyOntoTableId(srcTableId, destTableId, bq)
    val completedJob = scala.Option(job.waitFor(
      RetryOption.initialRetryDelay(Duration.ofSeconds(8)),
      RetryOption.maxRetryDelay(Duration.ofSeconds(60)),
      RetryOption.retryDelayMultiplier(2.0d),
      RetryOption.totalTimeout(Duration.ofMinutes(120))))

    completedJob match {
      case Some(j) =>
        val error = scala.Option(j.getStatus).map(_.getError)
        if (error.isDefined)
          Failure(new RuntimeException(error.get.toString))
        else Success(j)
      case _ =>
        Failure(new RuntimeException("Copy Job doesn't exist"))
    }
  }

  def copyOntoTableId(src: TableId, dest: TableId, bq: BigQuery): Job = {
    val jobConfig = CopyJobConfiguration.newBuilder(dest, src)
      .setCreateDisposition(CreateDisposition.CREATE_NEVER)
      .setWriteDisposition(WriteDisposition.WRITE_TRUNCATE)
      .build()
    val jobInfo = JobInfo.newBuilder(jobConfig).build()
    bq.create(jobInfo)
  }

  def createTable(c: Config, schema: StructType, destTableId: TableId, bigquery: BigQuery, expirationMs: scala.Option[Long] = None): Table ={
    require(c.clusterColumns.nonEmpty, "destination table does not exist, clusterColumns must not be empty")
    require(c.partitionColumn.nonEmpty, "destination table does not exist, partitionColumn must not be empty")
    val destTableSchema = if (c.partitionColumn.map(_.toLowerCase).contains("none")) {
      Mapping.convertStructType(schema.add(c.unusedColumnName, DateType))
    } else {
      Mapping.convertStructType(schema)
    }

    val destTableDefBuilder = StandardTableDefinition.newBuilder()
      .setLocation(c.bqLocation)
      .setSchema(destTableSchema)

    if (c.partitionColumn.contains("none") && c.clusterColumns.exists(_ != "none")) {
      // Only set null partition column if both partition column and cluster columns are provided
      destTableDefBuilder
        .setTimePartitioning(TimePartitioning
          .newBuilder(TimePartitioning.Type.DAY)
          .setField(c.unusedColumnName)
          .build())
    } else {
      c.partitionColumn match {
        case Some(partitionCol) if partitionCol != "none" =>
          // Only set partition column if partition column is set
          destTableDefBuilder
            .setTimePartitioning(TimePartitioning
              .newBuilder(TimePartitioning.Type.DAY)
              .setField(partitionCol)
              .build())
        case _ =>
          // Don't set a partition column if partition column is none
      }
    }

    if (c.clusterColumns.map(_.toLowerCase) != Seq("none")) {
      import scala.collection.JavaConverters.seqAsJavaListConverter
      destTableDefBuilder.setClustering(Clustering.newBuilder()
        .setFields(c.clusterColumns.map(_.toLowerCase).asJava).build())
    }

    val tableInfo = TableInfo.newBuilder(destTableId, destTableDefBuilder.build())

    expirationMs.foreach(x => tableInfo.setExpirationTime(System.currentTimeMillis() + x))

    bigquery.create(tableInfo.build())
  }
}
