package streaming.core.stream

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.StreamingQueryListener
import streaming.core.{StreamingproJobInfo, StreamingproJobManager, StreamingproJobType}
import streaming.log.{Logging, WowLog}

import scala.collection.JavaConverters._

/**
  * 2019-01-21 WilliamZhu(allwefantasy@gmail.com)
  */
object MLSQLStreamManager extends Logging with WowLog {
  private val store = new java.util.concurrent.ConcurrentHashMap[String, StreamingproJobInfo]()

  def addStore(job: StreamingproJobInfo) = {
    store.put(job.groupId, job)
  }

  def removeStore(groupId: String) = {
    store.remove(groupId)
  }

  def getJob(groupId: String) = {
    store.asScala.get(groupId)
  }

  def start(sparkSession: SparkSession) = {
    logInfo("Start streaming job monitor....")
    sparkSession.streams.addListener(new MLSQLStreamingQueryListener)
  }

  def close = {

  }
}

class MLSQLStreamingQueryListener extends StreamingQueryListener with Logging with WowLog {

  def sync(name: String, id: String) = {
    // first we should check by name, since before the stream is really stared, we have record the name in
    // StreamingproJobManager
    StreamingproJobManager.getJobInfo.filter(f => f._2.jobType == StreamingproJobType.STREAM
      && (f._2.jobName == name)).headOption match {
      case Some(job) =>
        if (job._2.groupId != id) {
          logInfo(format(
            s"""
               |StreamingproJobManager:${job._2.jobName}
               |Spark streams: ${name}
               |Action: sync
               |Reason:: Job is not synced before.
             """.stripMargin))
          //onQueryStarted is stared before we acquire info from StreamingQuery
          StreamingproJobManager.addJobManually(job._2.copy(groupId = id))
        }
      case None =>
        // we only care when stream is restore from ck without MLSQL instance restart
        // restore from  StreamManager.store
        MLSQLStreamManager.getJob(id) match {
          case Some(job) =>
            logInfo(format(
              s"""
                 |StreamingproJobManager:${job.jobName}
                 |Spark streams: ${name}
                 |Action: sync
                 |Reason:: Job is not in StreamingProManager but in StreamingproJobManager.
             """.stripMargin))
            StreamingproJobManager.addJobManually(job)
          case None =>
            // this  should not happen,throw exception
            throw new RuntimeException(s"MLSQL have unsync stream: ${name}")
        }
    }
  }

  override def onQueryStarted(event: StreamingQueryListener.QueryStartedEvent): Unit = {
    sync(event.name, event.id.toString)

  }

  override def onQueryProgress(event: StreamingQueryListener.QueryProgressEvent): Unit = {
    sync(event.progress.name, event.progress.id.toString)
  }

  override def onQueryTerminated(event: StreamingQueryListener.QueryTerminatedEvent): Unit = {
    MLSQLStreamManager.removeStore(event.id.toString)
    StreamingproJobManager.getJobInfo.filter(f => f._2.jobType == StreamingproJobType.STREAM
      && f._2.groupId == event.id.toString).headOption match {
      case Some(job) =>
        StreamingproJobManager.removeJobManually(job._1)
      case None =>
    }
  }
}
