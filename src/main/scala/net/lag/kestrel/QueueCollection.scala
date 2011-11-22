/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.kestrel

import java.io.File
import java.util.concurrent.CountDownLatch
import scala.collection.mutable
import com.twitter.conversions.time._
import com.twitter.libkestrel._
import com.twitter.libkestrel.config._
import com.twitter.logging.Logger
import com.twitter.ostrich.stats.Stats
import com.twitter.util.{Duration, Future, Time, Timer}
import config._

class InaccessibleQueuePath extends Exception("Inaccessible queue path: Must be a directory and writable")

class QueueCollection(queueFolder: String, timer: Timer,
                      defaultQueueConfig: JournaledQueueConfig,
                      queueConfigs: Seq[JournaledQueueConfig]) {
  private val log = Logger.get(getClass.getName)

  private val path = new File(queueFolder)

  if (! path.isDirectory) {
    path.mkdirs()
  }
  if (! path.isDirectory || ! path.canWrite) {
    throw new InaccessibleQueuePath
  }

  private[this] val queueConfigMap = queueConfigs.map { config => (config.name, config) }.toMap
  private val queues = new mutable.HashMap[String, JournaledQueue]
  private val fanout_queues = new mutable.HashMap[String, mutable.HashSet[String]]
  @volatile private var shuttingDown = false

  private def buildQueue(name: String, realName: String, path: String) = {
    if ((name contains ".") || (name contains "/") || (name contains "~")) {
      throw new Exception("Queue name contains illegal characters (one of: ~ . /).")
    }
    val config = queueConfigMap.getOrElse(name, defaultQueueConfig.copy(name = name))
    log.info("Setting up queue %s: %s", realName, config)
    new JournaledQueue(config, new File(path), timer)
  }

  // preload any queues
  def loadQueues() {
    Journal.getQueueNamesFromFolder(path) map { writer(_) }
  }

  def queueNames: List[String] = synchronized {
    queues.keys.toList
  }

  def currentItems = queues.values.foldLeft(0L) { _ + _.items }
  def currentBytes = queues.values.foldLeft(0L) { _ + _.bytes }

  /**
   * Get a named queue, creating it if necessary.
   */
  def writer(name: String): Option[JournaledQueue] = synchronized {
    if (shuttingDown) {
      None
    } else {
      Some(queues.get(name) getOrElse {
        // only happens when creating a queue for the first time.
        val q = buildQueue(name, name, path.getPath)
        queues(name) = q
        q
      })
    }
  }

  def reader(name: String): Option[JournaledQueue#Reader] = {
    val (writerName, readerName) = if (name contains '+') {
      val names = name.split("\\+", 2)
      (names(0), names(1))
    } else {
      (name, "")
    }
    writer(writerName).map { _.reader(readerName) }
  }

  /**
   * Add an item to a named queue. Will not return until the item has been synchronously added
   * and written to the queue journal file.
   *
   * @return true if the item was added; false if the server is shutting down
   */
  def add(key: String, data: Array[Byte], expiry: Option[Time], addTime: Time): Boolean = {
    writer(key) flatMap { q =>
      q.put(data, addTime, expiry) map { future =>
        future map { _ => Stats.incr("total_items") }
        true
      }
    } getOrElse(false)
  }

  def add(key: String, item: Array[Byte]): Boolean = add(key, item, None, Time.now)
  def add(key: String, item: Array[Byte], expiry: Option[Time]): Boolean = add(key, item, expiry, Time.now)

  /**
   * Retrieve an item from a queue and pass it to a continuation. If no item is available within
   * the requested time, or the server is shutting down, None is passed.
   */
  def remove(key: String, deadline: Option[Time] = None, transaction: Boolean = false, peek: Boolean = false): Future[Option[QueueItem]] = {
    reader(key) match {
      case None =>
        Future.value(None)
      case Some(q) =>
        val future = if (peek) {
          q.peek()
        } else {
          q.get(deadline)
        }
        future.map { itemOption =>
          itemOption match {
            case None => {
              Stats.incr("get_misses")
            }
            case Some(item) => {
              Stats.incr("get_hits")
              if (!transaction) q.commit(item.id)
            }
          }
          itemOption
        }
    }
  }

  def unremove(key: String, xid: Long) {
    reader(key) map { q => q.unget(xid) }
  }

  def confirmRemove(key: String, xid: Long) {
    reader(key) map { q => q.commit(xid) }
  }

  def flush(key: String) {
    reader(key) map { q => q.flush() }
  }

  def delete(name: String): Unit = synchronized {
    if (!shuttingDown) {
      queues.get(name) foreach { q =>
        q.erase()
        queues -= name
      }
      if (name contains '+') {
        val (writerName, readerName) = {
          val names = name.split("\\+", 2)
          (names(0), names(1))
        }
        queues(writerName).dropReader(readerName)
      }
    }
  }

  def flushExpired(name: String): Int = {
    if (shuttingDown) {
      0
    } else {
      writer(name) map { _.discardExpired() } getOrElse(0)
    }
  }

  def flushAllExpired(): Int = {
    queueNames.foldLeft(0) { (sum, qName) => sum + flushExpired(qName) }
  }


  /* FIXME
  def stats(key: String): Array[(String, String)] = queue(key) match {
    case None => Array[(String, String)]()
    case Some(q) =>
      q.dumpStats() ++
        fanout_queues.get(key).map { qset => ("children", qset.mkString(",")) }.toList
  }
  */

  /**
   * Shutdown this queue collection. Any future queue requests will fail.
   */
  def shutdown(): Unit = synchronized {
    if (shuttingDown) {
      return
    }
    shuttingDown = true
    for ((name, q) <- queues) {
      // synchronous, so the journals are all officially closed before we return.
      q.close
    }
    queues.clear
  }
}
