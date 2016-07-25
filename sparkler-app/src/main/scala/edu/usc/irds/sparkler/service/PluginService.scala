/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.usc.irds.sparkler.service

import java.net.URL
import java.util.Properties

import edu.usc.irds.sparkler._
import edu.usc.irds.sparkler.model.SparklerJob
import org.apache.felix.main.AutoProcessor
import org.osgi.framework.ServiceReference
import org.osgi.framework.launch.{Framework, FrameworkFactory}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Source

/**
  * A service for loading plugins/extensions
  *
  * @since Sparkler 0.1
  */
class PluginService {

  val LOG = LoggerFactory.getLogger(PluginService.getClass)

  // This map has two functions
  // 1) Keys are set of known extensions
  // 2) Values are the chaining implementation class
  val knownExtensions: Map[Class[_ <: ExtensionPoint], Class[_ <: ExtensionChain[_]]] = Map(
    classOf[URLFilter] -> classOf[URLFilters] //Add more extensions and chains here
  )

  var serviceLoader:Framework = null

  // This map keeps cache of all active instances
  val registry = new mutable.HashMap[Class[_ <: ExtensionPoint], ExtensionPoint]


  /**
    * <p>
    * Loads the configuration properties in the configuration property file
    * associated with the Felix framework installation; these properties
    * are accessible to the framework and to bundles and are intended
    * for configuration purposes.
    * </p>
    *
    * @return A <tt>Map</tt> instance or <tt>null</tt> if there was an error.
    **/
  def loadFelixConfig(): mutable.Map[String, String] = {
    var map:mutable.Map[String, String] = null
    val prop:Properties = new Properties()
    prop.load(getClass().getResourceAsStream(Constants.file.FELIX_CONFIG))
    map = prop.asScala
    map
  }


  /**
    * Simple method to parse META-INF/services file for framework factory.
    * Currently, it assumes the first non-commented line is the class name
    * of the framework factory implementation.
    *
    * @return The created <tt>FrameworkFactory</tt> instance.
    * @throws Exception if any errors occur.
    **/
  def getFelixFrameworkFactory: FrameworkFactory = {
    val url:URL = getClass().getResource(Constants.file.FELIX_FRAMEWORK_FACTORY)
    if (url != null) {
      val reader = Source.fromURL(url).bufferedReader()
      try {
        var line:String = ""
        while ({line = reader.readLine() ; line != null}) {
          line = line.trim()
          if (line.length() > 0 && line.charAt(0) != '#') {
            getClass.getClassLoader.loadClass(line).newInstance().asInstanceOf[FrameworkFactory]
          }
        }
      }
      finally {
        if (reader != null) {
          reader.close()
        }
      }
    }
    throw new SparklerException("Error Loading the Felix Framework Factory Instance")
  }


  def load(job:SparklerJob): Unit ={

    // Load Felix Configuration Properties
    var felixConfig:mutable.Map[String, String] = loadFelixConfig()
    if (felixConfig == null) {
      throw new SparklerException(s"Could not load Felix Configuration Properties file: "
        + s"${Constants.file.FELIX_CONFIG}");
    }

    LOG.info("Felix Configuration loaded successfully")

    // Setting configuration to Auto Deploy Felix Bundles
    felixConfig(AutoProcessor.AUTO_DEPLOY_DIR_PROPERTY) = Constants.file.FELIX_BUNDLE_DIR

    // Register a Shutdown Hook with JVM to make sure Felix framework is cleanly
    // shutdown when JVM exits
    Runtime.getRuntime().addShutdownHook(new Thread(s"Felix Framework for ${job.id}") {
      override def run: Unit = {
        try {
          if (serviceLoader != null) {
            serviceLoader.stop()
            serviceLoader.waitForStop(0)
          }
        }
        catch {
          case e: Exception => e.printStackTrace(); throw new SparklerException("Error Stopping the Felix Framework")
        }
      }
    })

    // Creating an instance of the Apache Felix framework
    val felixFactory:FrameworkFactory = getFelixFrameworkFactory
    serviceLoader = felixFactory.newFramework(felixConfig.asJava)

    // Initialize the framework but don't start it yet
    serviceLoader.init()

    // Use the system bundle context to process the auto-deploy
    // and auto-install/auto-start properties.
    AutoProcessor.process(felixConfig.asJava, serviceLoader.getBundleContext)

    // Start the Felix Framework
    serviceLoader.start()

  }

  /**
    * Checks if class is a sparkler extension
    *
    * @param clazz the class
    * @return true if the given class is an extension
    */
  def isExtensionPoint(clazz:Class[_]): Boolean = clazz != null && classOf[ExtensionPoint].isAssignableFrom(clazz)

  /**
    * Detects the point where an extension can be plugged into sparkler
    *
    * @param extension an instance of the extension
    * @return A point where the extension can be plugged
    */
  def detectExtensionPoint(extension: ExtensionPoint): Option[Class[_ <: ExtensionPoint]] ={
    val buffer = mutable.Queue[Class[_]](extension.getClass)
    var result:Option[Class[_ <: ExtensionPoint]] = None
    var detected = false
      while (!detected && buffer.nonEmpty){
        val clazz = buffer.dequeue()
        val extensionClass = clazz.asInstanceOf[Class[_ <: ExtensionPoint]]
        if (knownExtensions.contains(extensionClass)) {
          result = Some(extensionClass)
          detected = true
        }
        if (isExtensionPoint(clazz.getSuperclass)){
          buffer += clazz.getSuperclass
        }
        buffer ++= clazz.getInterfaces.filter(isExtensionPoint)
      }
    result
  }

  /**
    * gets list of active extensions for a given point
    *
    * @param point extension point
    * @return extension
    */
  def getExtension[X <: ExtensionPoint](point:Class[X]):Option[X] = {
    //if (registry.contains(point)) Some(registry(point).asInstanceOf[X]) else None
    val references: Array[ServiceReference] = serviceLoader.getBundleContext.getAllServiceReferences(point.getName, null)
    if (references != null || references.length > 0) Some(serviceLoader.getBundleContext.getService(references(0)).asInstanceOf[X]) else None
  }
}

object PluginService {

  //TODO: weak hash map + bounded size + with a sensible expiration policy like LRU
  val cache = new mutable.HashMap[SparklerJob, PluginService]()

  def getExtension[X <: ExtensionPoint](point:Class[X], job: SparklerJob):Option[X] = {
    if (!cache.contains(job)){
      //lazy initialization for distributed mode (wherever this code gets executed)
      val service = new PluginService()
      service.load(job)
      cache.put(job, service)
    }
    cache(job).getExtension(point)
  }
}