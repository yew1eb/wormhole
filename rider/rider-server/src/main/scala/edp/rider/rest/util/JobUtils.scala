/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */


package edp.rider.rest.util

import com.alibaba.fastjson.{JSON, JSONObject}
import edp.rider.RiderStarter.modules
import edp.rider.common.JobStatus.JobStatus
import edp.rider.common.{Action, JobStatus, RiderConfig, RiderLogger}
import edp.rider.rest.persistence.entities.{Instance, Job, NsDatabase, StartConfig}
import edp.rider.rest.util.CommonUtils._
import edp.rider.rest.util.FlowUtils._
import edp.rider.rest.util.NamespaceUtils._
import edp.rider.rest.util.NsDatabaseUtils._
import edp.rider.spark.SparkStatusQuery.getSparkJobStatus
import edp.rider.spark.SubmitSparkJob
import edp.rider.spark.SubmitSparkJob._
import edp.rider.wormhole._
import edp.wormhole.common.ConnectionConfig
import edp.wormhole.common.util.CommonUtils._
import edp.wormhole.common.util.DateUtils._
import edp.wormhole.common.util.JsonUtils._

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await

object JobUtils extends RiderLogger {

  def getBatchJobConfigConfig(job: Job) =
    BatchJobConfig(getSourceConfig(job.sourceNs, job.eventTsStart, job.eventTsEnd, job.sourceType, job.sourceConfig),
      getTranConfig(job.tranConfig.getOrElse(""), job.sinkNs),
      getSinkConfig(job.sinkNs, job.sinkConfig.getOrElse("")),
      getJobConfig(job.name, job.sparkConfig))

  def getSourceConfig(sourceNs: String, eventTsStart: String = null, eventTsEnd: String = null, sourceType: String = null, sourceConfig: Option[String]) = {
    val eventTsStartFinal = if (eventTsStart != null && eventTsStart != "") eventTsStart else "19700101000000"
    val eventTsEndFinal = if (eventTsEnd != null && eventTsEnd != "") eventTsEnd else "30000101000000"
    val sourceTypeFinal = if (sourceType != null && sourceType != "") sourceType else "hdfs_txt"
    val specialConfig = if (sourceConfig.isDefined && sourceConfig.get != "" && sourceConfig.get.contains("protocol")) Some(base64byte2s(getConsumptionProtocol(sourceConfig.get).trim.getBytes())) else None
    val (instance, db, _) = modules.namespaceDal.getNsDetail(sourceNs)
    SourceConfig(eventTsStartFinal, eventTsEndFinal, sourceNs, getConnConfig(instance, db, sourceType), getSourceProcessClass(sourceTypeFinal), specialConfig)
  }

  def getSinkConfig(sinkNs: String, sinkConfig: String) = {
    val (instance, db, ns) = modules.namespaceDal.getNsDetail(sinkNs)
    val maxRecord =
      if (sinkConfig != "" && sinkConfig != null && JSON.parseObject(sinkConfig).containsKey("maxRecordPerPartitionProcessed"))
        JSON.parseObject(sinkConfig).getIntValue("maxRecordPerPartitionProcessed")
      else RiderConfig.spark.jobMaxRecordPerPartitionProcessed
    val specialConfig =
      if (sinkConfig != "" && sinkConfig != null && JSON.parseObject(sinkConfig).containsKey("sink_specific_config"))
        Some(base64byte2s(JSON.parseObject(sinkConfig).getString("sink_specific_config").trim.getBytes()))
      else None

    val sinkKeys = if (ns.nsSys == "hbase") Some(FlowUtils.getRowKey(specialConfig.get)) else ns.keys

    val projection = if (sinkConfig != "" && sinkConfig != null && JSON.parseObject(sinkConfig).containsKey("sink_output")) {
      Some(JSON.parseObject(sinkConfig).getString("sink_output").trim)
    } else {
      None
    }
    SinkConfig(sinkNs, getConnConfig(instance, db), maxRecord, Some(getSinkProcessClass(ns.nsSys, ns.sinkSchema)), specialConfig, sinkKeys, projection)
  }

  def getTranConfig(tranConfig: String, sinkNs: String) = {
    if (tranConfig != "" && tranConfig != null) {
      val tranClass = JSON.parseObject(tranConfig)
      val action = if (tranClass.containsKey("action") && tranClass.getString("action").nonEmpty) {
        if (tranClass.getString("action").contains("edp.wormhole.batchjob.transform.Snapshot")) {
          val ns = modules.namespaceDal.getNamespaceByNs(sinkNs).get
          val swiftsSpec =
            if (tranClass.containsKey("swifts_specific_config"))
              tranClass.getJSONObject("swifts_specific_config")
            else new JSONObject()
          swiftsSpec.fluentPut("table_keys", ns.keys.getOrElse(""))
          tranClass.fluentPut("swifts_specific_config", swiftsSpec.toString)
        }
        Some(base64byte2s(tranClass.getString("action").trim.getBytes))
      } else None
      val specialConfig = if (tranClass.containsKey("swifts_specific_config")) Some(tranClass.getString("swifts_specific_config")) else None
      Some(TransformationConfig(action, specialConfig))
    }
    else None
  }

  def getJobConfig(name: String, sparkConfig: Option[String]) = {
    val sqlShufflePartition =
      if (sparkConfig != null && sparkConfig.isDefined && sparkConfig.get != "") {
        val index = sparkConfig.get.indexOf("spark.sql.shuffle.partitions=")
        if (index >= 0) {
          riderLogger.info("getJobConfig contains spark.sql.shuffle.partitions=")
          val length = "spark.sql.shuffle.partitions=".size
          val lastPart = sparkConfig.get.indexOf(",", index + length)
          val endIndex = if (lastPart < 0) sparkConfig.get.length else lastPart
          Some(sparkConfig.get.substring(index + length, endIndex).toInt)
        } else {
          riderLogger.info("getJobConfig DO NOT contains spark.sql.shuffle.partitions=")
          None
        }
      } else {
        riderLogger.info("getJobConfig is none. Do not contains spark.sql.shuffle.partitions=")
        None
      }
    JobConfig(name, "yarn-cluster", sqlShufflePartition)
  }

  def getSourceProcessClass(sourceType: String): String = {
    sourceType match {
      case "hdfs_txt" => "edp.wormhole.batchjob.source.SourceHdfs"
      case _ =>
        riderLogger.error(s"this sourceType $sourceType isn't supported now")
        throw new Exception(s"this sourceType $sourceType isn't supported now")
    }
  }

  def getConsumptionProtocol(sourceConfig: String): String = {
    val protocol = JSON.parseObject(sourceConfig).getString("protocol")
    protocol match {
      case "increment" => "{\"initial\": false, \"increment\": true}"
      case "initial" => "{\"initial\": true, \"increment\": false}"
      case "all" => "{\"initial\": true, \"increment\": true}"
    }
  }


  def getConnConfig(instance: Instance, db: NsDatabase, sourceType: String = null): ConnectionConfig = {
    val connUrl =
      if (sourceType == null || sourceType == "" || !sourceType.contains("hdfs")) getConnUrl(instance, db)
      else RiderConfig.spark.hdfs_root
    ConnectionConfig(connUrl, db.user, db.pwd, getDbConfig(instance.nsSys, db.config.getOrElse("")))
  }

  def startJob(job: Job) = {
    runShellCommand(s"rm -rf ${SubmitSparkJob.getLogPath(job.name)}")
    val startConfig: StartConfig = if (job.startConfig.isEmpty) null else json2caseClass[StartConfig](job.startConfig)
    val command = generateStreamStartSh(s"'''${base64byte2s(caseClass2json(getBatchJobConfigConfig(job)).trim.getBytes)}'''", job.name,
      if (startConfig != null) startConfig else StartConfig(RiderConfig.spark.driverCores, RiderConfig.spark.driverMemory, RiderConfig.spark.executorNum, RiderConfig.spark.executorMemory, RiderConfig.spark.executorCores),
      if (job.sparkConfig.isDefined && !job.sparkConfig.get.isEmpty) job.sparkConfig.get else Seq(RiderConfig.spark.driverExtraConf, RiderConfig.spark.executorExtraConf).mkString(",").concat(RiderConfig.spark.sparkConfig),
      "job"
    )
    riderLogger.info(s"start job ${job.id} command: ${command.replaceAll(" ", "")}")
    runShellCommand(command)
  }

  def genJobName(projectId: Long, sourceNs: String, sinkNs: String) = {
    val projectName = Await.result(modules.projectDal.findById(projectId), minTimeOut).head.name
    s"wormhole_${projectName}_job_${sourceNs}_${sinkNs}_$currentyyyyMMddHHmmss"
  }

  def refreshJob(id: Long) = {
    val job = Await.result(modules.jobDal.findById(id), minTimeOut).head
    val appInfo = getSparkJobStatus(job)
    modules.jobDal.updateJobStatus(job.id, appInfo)
    val startedTime = if (appInfo.startedTime != null) Some(appInfo.startedTime) else Some("")
    val stoppedTime = if (appInfo.finishedTime != null) Some(appInfo.finishedTime) else Some("")
    Job(job.id, job.name, job.projectId, job.sourceNs, job.sinkNs, job.sourceType, job.sparkConfig, job.startConfig, job.eventTsStart, job.eventTsEnd, job.sourceConfig,
      job.sinkConfig, job.tranConfig, appInfo.appState, Some(appInfo.appId), job.logPath, startedTime, stoppedTime, job.createTime, job.createBy, job.updateTime, job.updateBy)
  }

  def killJob(id: Long): String = {
    try {
      val job = refreshJob(id)
      try {
        if (job.status == "running" || job.status == "waiting") {
          val command = s"yarn application -kill ${job.sparkAppid.get}"
          riderLogger.info(s"stop job command: $command")
          runShellCommand(command)
          modules.jobDal.updateJobStatus(job.id, "stopping")
          "stopping"
        } else if (job.status == "failed") {
          modules.jobDal.updateJobStatus(job.id, "stopped")
          "stopped"
        }
        else job.status
      }
      catch {
        case ex: Exception =>
          riderLogger.error(s"job $id kill failed", ex)
          throw ex
      }
    } catch {
      case ex: Exception =>
        riderLogger.error(s"job $id kill failed", ex)
        throw ex
    }
  }

  def getDisableAction(job: Job) = {
    val projectNsSeq = modules.relProjectNsDal.getNsByProjectId(job.projectId)
    val nsSeq = new ListBuffer[String]
    nsSeq += job.sourceNs
    nsSeq += job.sinkNs
    var flag = true
    for (i <- nsSeq.indices) {
      if (!projectNsSeq.exists(_.startsWith(nsSeq(i))))
        flag = false
    }
    if (!flag) {
      if (job.status == "stopped") "modify,start,renew,stop"
      else "modify,start,renew"
    } else {
      JobStatus.jobStatus(job.status) match {
        case JobStatus.NEW => s"${Action.STOP}"
        case JobStatus.STARTING => s"${Action.START},${Action.STOP},${Action.DELETE}"
        case JobStatus.WAITING => s"${Action.START}"
        case JobStatus.RUNNING => s"${Action.START}"
        case JobStatus.STOPPING => s"${Action.START}"
        case JobStatus.FAILED => ""
        case JobStatus.STOPPED => s"${Action.STOP}"
        case JobStatus.DONE => s"${Action.STOP}"
      }
    }
  }
}
