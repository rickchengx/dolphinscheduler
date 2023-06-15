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

package org.apache.dolphinscheduler.dao.utils;

import static org.apache.dolphinscheduler.common.constants.Constants.CRC_SUFFIX;

import org.apache.dolphinscheduler.common.enums.Flag;
import org.apache.dolphinscheduler.common.utils.FileUtils;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.plugin.storage.api.StorageOperate;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.enums.DataType;
import org.apache.dolphinscheduler.plugin.task.api.enums.Direct;
import org.apache.dolphinscheduler.plugin.task.api.model.Parameter;

import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TaskCacheUtilsTest {

    private TaskInstance taskInstance;

    private TaskExecutionContext taskExecutionContext;

    private StorageOperate storageOperate;

    @BeforeEach
    void setUp() {
        String taskParams = "{\n" +
                "  \"localParams\": [\n" +
                "    {\n" +
                "      \"key\": \"a\",\n" +
                "      \"direct\": \"IN\",\n" +
                "      \"type\": \"VARCHAR\",\n" +
                "      \"value\": \"\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"key\": \"b\",\n" +
                "      \"direct\": \"IN\",\n" +
                "      \"type\": \"VARCHAR\",\n" +
                "      \"value\": \"bb\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"rawScript\": \"echo ${c}\\necho ${d}\",\n" +
                "  \"resourceList\": []\n" +
                "}";

        String varPool = "[\n" +
                "  {\n" +
                "    \"key\": \"c\",\n" +
                "    \"direct\": \"IN\",\n" +
                "    \"type\": \"VARCHAR\",\n" +
                "    \"value\": \"cc\"\n" +
                "  },\n" +
                "  {\n" +
                "    \"key\": \"k\",\n" +
                "    \"direct\": \"IN\",\n" +
                "    \"type\": \"VARCHAR\",\n" +
                "    \"value\": \"kk\"\n" +
                "  }\n" +
                "]";

        taskInstance = new TaskInstance();
        taskInstance.setTaskParams(taskParams);
        taskInstance.setVarPool(varPool);
        taskInstance.setTaskCode(123L);
        taskInstance.setTaskDefinitionVersion(1);
        taskInstance.setIsCache(Flag.YES);

        taskExecutionContext = new TaskExecutionContext();
        Parameter parameter = new Parameter();
        parameter.setKey("a");
        parameter.setDirect(Direct.IN);
        parameter.setType(DataType.VARCHAR);
        parameter.setValue("aa");
        Map<String, Parameter> prepareParamsMap = new HashMap<>();
        prepareParamsMap.put("a", parameter);
        taskExecutionContext.setPrepareParamsMap(prepareParamsMap);

        storageOperate = Mockito.mock(StorageOperate.class);
    }

    @Test
    void testRevertCacheKey() {
        Pair<Integer, String> taskIdAndCacheKey1 = TaskCacheUtils.revertCacheKey(null);
        Assertions.assertEquals(Pair.of(-1, ""), taskIdAndCacheKey1);

        Pair<Integer, String> taskIdAndCacheKey2 = TaskCacheUtils.revertCacheKey("123");
        Assertions.assertEquals(Pair.of(-1, "123"), taskIdAndCacheKey2);

        Pair<Integer, String> taskIdAndCacheKey3 = TaskCacheUtils.revertCacheKey("1-123");
        Assertions.assertEquals(Pair.of(1, "123"), taskIdAndCacheKey3);

        Pair<Integer, String> taskIdAndCacheKey4 = TaskCacheUtils.revertCacheKey("1-123-4");
        Assertions.assertEquals(Pair.of(-1, ""), taskIdAndCacheKey4);
    }

    @Test
    void testGetScriptVarInSet() {
        List<String> scriptVarInSet = TaskCacheUtils.getScriptVarInSet(taskInstance);
        List<String> except = new ArrayList<>(Arrays.asList("c", "d"));
        Assertions.assertEquals(except, scriptVarInSet);
    }

    @Test
    void TestGetTaskInputVarPoolData() {
        TaskCacheUtils.getTaskInputVarPoolData(taskInstance, taskExecutionContext, storageOperate);
        // only a=aa and c=cc will influence the result,
        // b=bb is a fixed value, will be considered in task version
        // k=kk is not in task params, will be ignored
        String except =
                "[{\"key\":\"a\",\"direct\":\"IN\",\"type\":\"VARCHAR\",\"value\":\"aa\"},{\"key\":\"c\",\"direct\":\"IN\",\"type\":\"VARCHAR\",\"value\":\"cc\"}]";
        Assertions.assertEquals(except,
                TaskCacheUtils.getTaskInputVarPoolData(taskInstance, taskExecutionContext, storageOperate));
    }

    @Test
    void TestGenerateCacheKey() {
        String cacheKeyBase = TaskCacheUtils.generateCacheKey(taskInstance, taskExecutionContext, storageOperate);
        Parameter parameterI = new Parameter();
        parameterI.setKey("i");
        parameterI.setDirect(Direct.IN);
        parameterI.setType(DataType.VARCHAR);
        parameterI.setValue("ii");
        taskExecutionContext.getPrepareParamsMap().put("i", parameterI);
        String cacheKeyNew = TaskCacheUtils.generateCacheKey(taskInstance, taskExecutionContext, storageOperate);
        // i will not influence the result, because task instance not use it
        Assertions.assertEquals(cacheKeyBase, cacheKeyNew);

        Parameter parameterD = new Parameter();
        parameterD.setKey("d");
        parameterD.setDirect(Direct.IN);
        parameterD.setType(DataType.VARCHAR);
        parameterD.setValue("dd");
        taskExecutionContext.getPrepareParamsMap().put("i", parameterD);
        String cacheKeyD = TaskCacheUtils.generateCacheKey(taskInstance, taskExecutionContext, storageOperate);
        // d will influence the result, because task instance use it
        Assertions.assertNotEquals(cacheKeyBase, cacheKeyD);

        taskInstance.setTaskDefinitionVersion(100);
        String cacheKeyE = TaskCacheUtils.generateCacheKey(taskInstance, taskExecutionContext, storageOperate);
        // task definition version is changed, so cache key changed
        Assertions.assertNotEquals(cacheKeyD, cacheKeyE);

        taskInstance.setEnvironmentConfig("export PYTHON_HOME=/bin/python3");
        String cacheKeyF = TaskCacheUtils.generateCacheKey(taskInstance, taskExecutionContext, storageOperate);
        // EnvironmentConfig is changed, so cache key changed
        Assertions.assertNotEquals(cacheKeyE, cacheKeyF);
    }

    @Test
    void testGetCacheKey() {
        String cacheKey = TaskCacheUtils.generateTagCacheKey(1, "123");
        Assertions.assertEquals("1-123", cacheKey);
    }

    @Test
    void testReplaceWithCheckSum() {
        String content = "abcdefg";
        String filePath = "test/testFile.txt";
        FileUtils.writeContent2File(content, filePath + CRC_SUFFIX);

        Parameter parameter = new Parameter();
        parameter.setKey("f1");
        parameter.setValue("testFile.txt");
        parameter.setType(DataType.FILE);
        parameter.setDirect(Direct.IN);
        TaskExecutionContext taskExecutionContext = new TaskExecutionContext();
        taskExecutionContext.setExecutePath("test");
        taskExecutionContext.setTenantCode("aaa");

        String crc = TaskCacheUtils.getValCheckSum(parameter, taskExecutionContext, storageOperate);
        Assertions.assertEquals(crc, content);
    }
}
