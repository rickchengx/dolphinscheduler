/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.server.worker.utils;

import static org.apache.dolphinscheduler.common.constants.Constants.CRC_SUFFIX;

import org.apache.dolphinscheduler.common.utils.DateUtils;
import org.apache.dolphinscheduler.common.utils.FileUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.plugin.storage.api.StorageOperate;
import org.apache.dolphinscheduler.plugin.task.api.TaskException;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.enums.DataType;
import org.apache.dolphinscheduler.plugin.task.api.enums.Direct;
import org.apache.dolphinscheduler.plugin.task.api.model.Parameter;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.zeroturnaround.zip.ZipUtil;

import com.fasterxml.jackson.databind.JsonNode;

@Slf4j
public class TaskFilesTransferUtils {

    // tmp path in local path for transfer
    final static String DOWNLOAD_TMP = ".DT_TMP";

    // suffix of the package file
    final static String PACK_SUFFIX = "_ds_pack.zip";

    // root path in resource storage
    final static String RESOURCE_TAG = "DATA_TRANSFER";

    private TaskFilesTransferUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * upload output files to resource storage
     *
     * @param taskExecutionContext is the context of task
     * @param storageOperate       is the storage operate
     * @throws TaskException TaskException
     */
    public static void uploadOutputFiles(TaskExecutionContext taskExecutionContext,
                                         StorageOperate storageOperate) throws TaskException {
        List<Parameter> varPools = getVarPools(taskExecutionContext);
        // get map of varPools for quick search
        Map<String, Parameter> varPoolsMap = varPools.stream().collect(Collectors.toMap(Parameter::getKey, x -> x));

        // get OUTPUT FILE parameters
        List<Parameter> localParamsProperty = getFileLocalParams(taskExecutionContext, Direct.OUT);

        if (localParamsProperty.isEmpty()) {
            return;
        }

        log.info("Upload output files ...");
        for (Parameter parameter : localParamsProperty) {
            // get local file path
            String path = String.format("%s/%s", taskExecutionContext.getExecutePath(), parameter.getValue());
            String srcPath = packIfDir(path);

            // get crc file path
            String srcCRCPath = srcPath + CRC_SUFFIX;
            try {
                FileUtils.writeContent2File(FileUtils.getFileChecksum(path), srcCRCPath);
            } catch (IOException ex) {
                throw new TaskException(ex.getMessage(), ex);
            }

            // get remote file path
            String resourcePath = getResourcePath(taskExecutionContext, new File(srcPath).getName());
            String resourceCRCPath = resourcePath + CRC_SUFFIX;
            try {
                // upload file to storage
                String resourceWholePath =
                        storageOperate.getResourceFullName(taskExecutionContext.getTenantCode(), resourcePath);
                String resourceCRCWholePath =
                        storageOperate.getResourceFullName(taskExecutionContext.getTenantCode(), resourceCRCPath);
                log.info("{} --- Local:{} to Remote:{}", parameter, srcPath, resourceWholePath);
                storageOperate.upload(taskExecutionContext.getTenantCode(), srcPath, resourceWholePath, false, true);
                log.info("{} --- Local:{} to Remote:{}", "CRC file", srcCRCPath, resourceCRCWholePath);
                storageOperate.upload(taskExecutionContext.getTenantCode(), srcCRCPath, resourceCRCWholePath, false,
                        true);
            } catch (IOException ex) {
                throw new TaskException("Upload file to storage error", ex);
            }

            // update varPool
            Parameter oriProperty;
            // if the property is not in varPool, add it
            if (varPoolsMap.containsKey(parameter.getKey())) {
                oriProperty = varPoolsMap.get(parameter.getKey());
            } else {
                oriProperty = new Parameter(parameter.getKey(), Direct.OUT, DataType.FILE, parameter.getValue());
                varPools.add(oriProperty);
            }
            oriProperty.setKey(String.format("%s.%s", taskExecutionContext.getTaskName(), oriProperty.getKey()));
            oriProperty.setValue(resourcePath);
        }
        taskExecutionContext.setVarPool(JSONUtils.toJsonString(varPools));
    }

    /**
     * download upstream files from storage
     * only download files which are defined in the task parameters
     *
     * @param taskExecutionContext is the context of task
     * @param storageOperate       is the storage operate
     * @throws TaskException task exception
     */
    public static void downloadUpstreamFiles(TaskExecutionContext taskExecutionContext, StorageOperate storageOperate) {
        List<Parameter> varPools = getVarPools(taskExecutionContext);
        // get map of varPools for quick search
        Map<String, Parameter> varPoolsMap = varPools.stream().collect(Collectors.toMap(Parameter::getKey, x -> x));

        // get "IN FILE" parameters
        List<Parameter> localParamsProperty = getFileLocalParams(taskExecutionContext, Direct.IN);

        if (localParamsProperty.isEmpty()) {
            return;
        }

        String executePath = taskExecutionContext.getExecutePath();
        // data path to download packaged data
        String downloadTmpPath = String.format("%s/%s", executePath, DOWNLOAD_TMP);

        log.info("Download upstream files...");
        for (Parameter parameter : localParamsProperty) {
            Parameter inVarPool = varPoolsMap.get(parameter.getValue());
            if (inVarPool == null) {
                log.error("{} not in  {}", parameter.getValue(), varPoolsMap.keySet());
                throw new TaskException(String.format("Can not find upstream file using %s, please check the key",
                        parameter.getValue()));
            }

            String resourcePath = inVarPool.getValue();
            String targetPath = String.format("%s/%s", executePath, parameter.getKey());

            String downloadPath;
            // If the data is packaged, download it to a special directory (DOWNLOAD_TMP) and unpack it to the
            // targetPath
            boolean isPack = resourcePath.endsWith(PACK_SUFFIX);
            if (isPack) {
                downloadPath = String.format("%s/%s", downloadTmpPath, new File(resourcePath).getName());
            } else {
                downloadPath = targetPath;
            }

            try {
                String resourceWholePath =
                        storageOperate.getResourceFullName(taskExecutionContext.getTenantCode(), resourcePath);
                log.info("{} --- Remote:{} to Local:{}", parameter, resourceWholePath, downloadPath);
                storageOperate.download(taskExecutionContext.getTenantCode(), resourceWholePath, downloadPath, true);
            } catch (IOException ex) {
                throw new TaskException("Download file from storage error", ex);
            }

            // unpack if the data is packaged
            if (isPack) {
                File downloadFile = new File(downloadPath);
                log.info("Unpack {} to {}", downloadPath, targetPath);
                ZipUtil.unpack(downloadFile, new File(targetPath));
            }
        }

        // delete DownloadTmp Folder if DownloadTmpPath exists
        try {
            org.apache.commons.io.FileUtils.deleteDirectory(new File(downloadTmpPath));
        } catch (IOException e) {
            log.error("Delete DownloadTmpPath {} failed, this will not affect the task status", downloadTmpPath, e);
        }
    }

    /**
     * get local parameters property which type is FILE and direction is equal to direct
     *
     * @param taskExecutionContext is the context of task
     * @param direct               may be Direct.IN or Direct.OUT.
     * @return List<Property>
     */
    public static List<Parameter> getFileLocalParams(TaskExecutionContext taskExecutionContext, Direct direct) {
        List<Parameter> localParamsProperty = new ArrayList<>();
        JsonNode taskParams = JSONUtils.parseObject(taskExecutionContext.getTaskParams());
        for (JsonNode localParam : taskParams.get("localParams")) {
            Parameter parameter = JSONUtils.parseObject(localParam.toString(), Parameter.class);

            if (parameter.getDirect().equals(direct) && parameter.getType().equals(DataType.FILE)) {
                localParamsProperty.add(parameter);
            }
        }
        return localParamsProperty;
    }

    /**
     * get Resource path for manage files in storage
     *
     * @param taskExecutionContext is the context of task
     * @param fileName             is the file name
     * @return resource path, RESOURCE_TAG/DATE/ProcessDefineCode/ProcessDefineVersion_ProcessInstanceID/TaskName_TaskInstanceID_FileName
     */
    public static String getResourcePath(TaskExecutionContext taskExecutionContext, String fileName) {
        String date =
                DateUtils.formatTimeStamp(taskExecutionContext.getEndTime(), DateTimeFormatter.ofPattern("yyyyMMdd"));
        // get resource Folder: RESOURCE_TAG/DATE/ProcessDefineCode/ProcessDefineVersion_ProcessInstanceID
        String resourceFolder = String.format("%s/%s/%d/%d_%d", RESOURCE_TAG, date,
                taskExecutionContext.getProcessDefineCode(), taskExecutionContext.getProcessDefineVersion(),
                taskExecutionContext.getProcessInstanceId());
        // get resource fileL: resourceFolder/TaskName_TaskInstanceID_FileName
        return String.format("%s/%s_%s_%s", resourceFolder, taskExecutionContext.getTaskName().replace(" ", "_"),
                taskExecutionContext.getTaskInstanceId(), fileName);
    }

    /**
     * get varPool from taskExecutionContext
     *
     * @param taskExecutionContext is the context of task
     * @return List<Property>
     */
    public static List<Parameter> getVarPools(TaskExecutionContext taskExecutionContext) {
        List<Parameter> varPools = new ArrayList<>();

        // get varPool
        String varPoolString = taskExecutionContext.getVarPool();
        if (StringUtils.isEmpty(varPoolString)) {
            return varPools;
        }
        // parse varPool
        for (JsonNode varPoolData : JSONUtils.parseArray(varPoolString)) {
            Parameter parameter = JSONUtils.parseObject(varPoolData.toString(), Parameter.class);
            varPools.add(parameter);
        }
        return varPools;
    }

    /**
     * If the path is a directory, pack it and return the path of the package
     *
     * @param path is the input path, may be a file or a directory
     * @return new path
     */
    public static String packIfDir(String path) throws TaskException {
        File file = new File(path);
        if (!file.exists()) {
            throw new TaskException(String.format("%s dose not exists", path));
        }
        String newPath;
        if (file.isDirectory()) {
            newPath = file.getPath() + PACK_SUFFIX;
            log.info("Pack {} to {}", path, newPath);
            ZipUtil.pack(file, new File(newPath));
        } else {
            newPath = path;
        }
        return newPath;
    }
}
