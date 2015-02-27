/*
 * Licensed to Laurent Broudoux (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.github.lbroudoux.microcks.service;

import com.github.lbroudoux.microcks.domain.*;
import com.github.lbroudoux.microcks.repository.*;
import com.github.lbroudoux.microcks.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Authenticator;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.Map;

/**
 * Bean defining service operations around Service domain objects.
 * @author laurent
 */
@org.springframework.stereotype.Service
public class ServiceService {

   /** A simple logger for diagnostic messages. */
   private static Logger log = LoggerFactory.getLogger(ServiceService.class);

   @Autowired
   private ServiceRepository serviceRepository;

   @Autowired
   private ResourceRepository resourceRepository;

   @Autowired
   private RequestRepository requestRepository;

   @Autowired
   private ResponseRepository responseRepository;

   @Autowired
   private TestResultRepository testResultRepository;

   @Value("${network.username}")
   private final String username = null;

   @Value("${network.password}")
   private final String password = null;


   /**
    * Import definitions of services and bounded resources and messages into Microcks
    * repository. This uses a MockRepositoryImporter underhood.
    * @param repositoryUrl The String representing mock repository url.
    * @return The list of imported Services
    * @throws MockRepositoryImportException if something goes wrong (URL not reachable nor readable, etc...)
    */
   public List<Service> importServiceDefinition(String repositoryUrl) throws MockRepositoryImportException {
      log.info("Importing service definitions from file " + repositoryUrl);
      if (repositoryUrl.startsWith("http")){
         try {
            repositoryUrl = handleRemoteFileDownload(repositoryUrl);
         } catch (IOException ioe) {
            log.error("Exception while downloading " + repositoryUrl, ioe);
            throw new MockRepositoryImportException(repositoryUrl + " cannot be downloaded", ioe);
         }
      }

      // Retrieve the correct importer based on file path.
      MockRepositoryImporter importer = null;
      try {
         importer = MockRepositoryImporterFactory.getMockRepositoryImporter(new File(repositoryUrl));
      } catch (IOException ioe) {
         log.error("Exception while accessing file " + repositoryUrl, ioe);
         throw new MockRepositoryImportException(repositoryUrl + " cannot be found", ioe);
      }

      List<Service> services = importer.getServiceDefinitions();
      for (Service service : services){
         Service existingService = serviceRepository.findByNameAndVersion(service.getName(), service.getVersion());
         log.debug("Service [{}, {}] exists ? {}", service.getName(), service.getVersion(), existingService != null);
         if (existingService != null){
            service.setId(existingService.getId());
         }
         service = serviceRepository.save(service);

         // Remove resources previously attached to service.
         List<Resource> existingResources = resourceRepository.findByServiceId(service.getId());
         if (existingResources != null && existingResources.size() > 0){
            resourceRepository.delete(existingResources);
         }

         // Save new resources.
         List<Resource> resources = importer.getResourceDefinitions(service);
         for (Resource resource : resources){
            resource.setServiceId(service.getId());
         }
         resourceRepository.save(resources);

         for (Operation operation : service.getOperations()){
            String operationId = IdBuilder.buildOperationId(service, operation);

            // Remove messages previously attached to service.
            requestRepository.delete(requestRepository.findByOperationId(operationId));
            responseRepository.delete(responseRepository.findByOperationId(operationId));

            Map<Request, Response> messages = importer.getMessageDefinitions(service, operation);

            // Associate response with operation before saving.
            for (Response response : messages.values()){
               response.setOperationId(operationId);
            }
            responseRepository.save(messages.values());
            // Associate request with response and operation before saving.
            for (Request request : messages.keySet()){
               request.setOperationId(operationId);
               request.setResponseId(messages.get(request).getId());
            }
            requestRepository.save(messages.keySet());
         }
      }
      log.info("Having imported {} services definitions into repository", services.size());
      return services;
   }

   /**
    * Remove a Service and its bound documents using the service id.
    * @param id The identifier of service to remove.
    */
   public void deleteService(String id){
      // Get service to remove.
      Service service = serviceRepository.findOne(id);

      // Delete all resources first.
      resourceRepository.delete(resourceRepository.findByServiceId(id));

      // Delete all requests and responses bound to service operation.
      for (Operation operation : service.getOperations()){
         String operationId = IdBuilder.buildOperationId(service, operation);
         requestRepository.delete(requestRepository.findByOperationId(operationId));
         responseRepository.delete(responseRepository.findByOperationId(operationId));
      }

      // Delete all tests related to service.
      testResultRepository.delete(testResultRepository.findByServiceId(id));

      // Finally delete service.
      serviceRepository.delete(service);
      log.info("Service [{}] has been fully deleted", id);
   }

   /**
    * Update the default delay of a Service operation
    * @param id The identifier of service to update operation for
    * @param operationName The name of operation to update delay for
    * @param delay The new delay value for operation
    * @return True if operation has been found and updated, false otherwise.
    */
   public Boolean updateOperationDelay(BigInteger id, String operationName, int delay) {
      /*
      Service service = serviceRepository.findOne(id);
      for (Operation operation : service.getOperations()){
         if (operation.getName().equals(operationName)){
            operation.setDefaultDelay(delay);
            serviceRepository.save(service);
            return true;
         }
      }
      */
      return false;
   }

   /** Download a remote HTTP URL into a temporary local file. */
   private String handleRemoteFileDownload(String remoteUrl) throws IOException {
      // Build remote Url and local file.
      URL website = new URL(remoteUrl);
      String localFile = System.getProperty("java.io.tmpdir") + "/microcks-" + System.currentTimeMillis() + ".project";
      // Set authenticator instance.
      Authenticator.setDefault(new UsernamePasswordAuthenticator(username, password));
      ReadableByteChannel rbc = null;
      FileOutputStream fos = null;
      try {
         rbc = Channels.newChannel(website.openStream());
         // Transfer file to local.
         fos = new FileOutputStream(localFile);
         fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
      }
      finally {
         if (fos != null)
            fos.close();
         if (rbc != null)
            rbc.close();
      }
      return localFile;
   }
}
