/* Licensed under the Apache License, Version 2.0 (the "License");
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
 */
package org.flowable.form.engine.impl.cmd;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.flowable.editor.form.converter.FormJsonConverter;
import org.flowable.engine.common.api.FlowableException;
import org.flowable.engine.common.api.FlowableObjectNotFoundException;
import org.flowable.engine.common.impl.interceptor.Command;
import org.flowable.engine.common.impl.interceptor.CommandContext;
import org.flowable.form.api.FormInstance;
import org.flowable.form.engine.FormEngineConfiguration;
import org.flowable.form.engine.FormExpression;
import org.flowable.form.engine.impl.persistence.deploy.DeploymentManager;
import org.flowable.form.engine.impl.persistence.deploy.FormDefinitionCacheEntry;
import org.flowable.form.engine.impl.persistence.entity.FormDefinitionEntity;
import org.flowable.form.engine.impl.util.CommandContextUtil;
import org.flowable.form.model.ExpressionFormField;
import org.flowable.form.model.FormField;
import org.flowable.form.model.FormFieldTypes;
import org.flowable.form.model.FormModel;
import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Tijs Rademakers
 */
public class GetFormModelWithVariablesCmd implements Command<FormModel>, Serializable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GetFormModelWithVariablesCmd.class);

    private static final long serialVersionUID = 1L;
    
    protected static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-M-d");

    protected String formDefinitionKey;
    protected String parentDeploymentId;
    protected String formDefinitionId;
    protected String processInstanceId;
    protected String taskId;
    protected String tenantId;
    protected Map<String, Object> variables;

    public GetFormModelWithVariablesCmd(String formDefinitionKey, String formDefinitionId, String processInstanceId, String taskId, Map<String, Object> variables) {
        initializeValues(formDefinitionKey, formDefinitionId, null, variables);
        this.processInstanceId = processInstanceId;
        this.taskId = taskId;
    }

    public GetFormModelWithVariablesCmd(String formDefinitionKey, String parentDeploymentId, String formDefinitionId, String processInstanceId, String taskId, Map<String, Object> variables) {
        initializeValues(formDefinitionKey, formDefinitionId, null, variables);
        this.parentDeploymentId = parentDeploymentId;
        this.processInstanceId = processInstanceId;
        this.taskId = taskId;
    }

    public GetFormModelWithVariablesCmd(String formDefinitionKey, String parentDeploymentId, String formDefinitionId, String processInstanceId, String taskId, String tenantId, Map<String, Object> variables) {
        initializeValues(formDefinitionKey, formDefinitionId, null, variables);
        this.parentDeploymentId = parentDeploymentId;
        this.processInstanceId = processInstanceId;
        this.taskId = taskId;
        this.tenantId = tenantId;
    }

    public FormModel execute(CommandContext commandContext) {
        FormDefinitionCacheEntry formCacheEntry = resolveFormDefinition(commandContext);
        FormInstance formInstance = resolveFormInstance(formCacheEntry, commandContext);
        FormModel formModel = resolveFormModel(formCacheEntry, commandContext);
        fillFormFieldValues(formInstance, formModel, commandContext);
        return formModel;
    }

    protected void initializeValues(String formDefinitionKey, String formDefinitionId, String tenantId, Map<String, Object> variables) {
        this.formDefinitionKey = formDefinitionKey;
        this.formDefinitionId = formDefinitionId;
        this.tenantId = tenantId;
        if (variables != null) {
            this.variables = variables;
        } else {
            this.variables = new HashMap<>();
        }
    }

    protected void fillFormFieldValues(FormInstance formInstance, FormModel formDefinition, CommandContext commandContext) {

        FormEngineConfiguration formEngineConfiguration = CommandContextUtil.getFormEngineConfiguration();
        List<FormField> allFields = formDefinition.listAllFields();
        if (allFields != null) {

            Map<String, JsonNode> formInstanceFieldMap = new HashMap<>();
            if (formInstance != null) {
                fillFormInstanceValues(formInstance, formInstanceFieldMap, formEngineConfiguration.getObjectMapper());
                fillVariablesWithFormInstanceValues(formInstanceFieldMap, allFields);
            }

            for (FormField field : allFields) {
                if (field instanceof ExpressionFormField) {
                    ExpressionFormField expressionField = (ExpressionFormField) field;
                    FormExpression formExpression = formEngineConfiguration.getExpressionManager().createExpression(expressionField.getExpression());
                    try {
                        field.setValue(formExpression.getValue(variables));
                    } catch (Exception e) {
                        LOGGER.error("Error getting value for expression {} {}", expressionField.getExpression(), e.getMessage(), e);
                    }

                } else {
                    Object variableValue = variables.get(field.getId());
                    
                    if (variableValue != null) {
                        if (variableValue instanceof LocalDate) {
                            LocalDate dateVariable = (LocalDate) variableValue;
                            field.setValue(dateVariable.toString("yyyy-M-d"));
                            
                        } else if (variableValue instanceof Date) {
                            Date dateVariable = (Date) variableValue;
                            field.setValue(DATE_FORMAT.format(dateVariable));
                            
                        } else {
                            field.setValue(variableValue);
                        }
                    }
                }
            }
        }
    }

    protected FormDefinitionCacheEntry resolveFormDefinition(CommandContext commandContext) {
        DeploymentManager deploymentManager = CommandContextUtil.getFormEngineConfiguration().getDeploymentManager();

        // Find the form definition
        FormDefinitionEntity formDefinitionEntity = null;
        if (formDefinitionId != null) {

            formDefinitionEntity = deploymentManager.findDeployedFormDefinitionById(formDefinitionId);
            if (formDefinitionEntity == null) {
                throw new FlowableObjectNotFoundException("No form definition found for id = '" + formDefinitionId + "'", FormDefinitionEntity.class);
            }

        } else if (formDefinitionKey != null && (tenantId == null || FormEngineConfiguration.NO_TENANT_ID.equals(tenantId)) && parentDeploymentId == null) {

            formDefinitionEntity = deploymentManager.findDeployedLatestFormDefinitionByKey(formDefinitionKey);
            if (formDefinitionEntity == null) {
                throw new FlowableObjectNotFoundException("No form definition found for key '" + formDefinitionKey + "'", FormDefinitionEntity.class);
            }

        } else if (formDefinitionKey != null && tenantId != null && !FormEngineConfiguration.NO_TENANT_ID.equals(tenantId) && parentDeploymentId == null) {

            formDefinitionEntity = deploymentManager.findDeployedLatestFormDefinitionByKeyAndTenantId(formDefinitionKey, tenantId);
            if (formDefinitionEntity == null) {
                throw new FlowableObjectNotFoundException("No form definition found for key '" + formDefinitionKey + "' for tenant identifier " + tenantId, FormDefinitionEntity.class);
            }

        } else if (formDefinitionKey != null && (tenantId == null || FormEngineConfiguration.NO_TENANT_ID.equals(tenantId)) && parentDeploymentId != null) {

            formDefinitionEntity = deploymentManager.findDeployedLatestFormDefinitionByKeyAndParentDeploymentId(formDefinitionKey, parentDeploymentId);
            if (formDefinitionEntity == null) {
                throw new FlowableObjectNotFoundException("No form definition found for key '" + formDefinitionKey +
                        "' for parent deployment id " + parentDeploymentId, FormDefinitionEntity.class);
            }

        } else if (formDefinitionKey != null && tenantId != null && !FormEngineConfiguration.NO_TENANT_ID.equals(tenantId) && parentDeploymentId != null) {

            formDefinitionEntity = deploymentManager.findDeployedLatestFormDefinitionByKeyParentDeploymentIdAndTenantId(formDefinitionKey, parentDeploymentId, tenantId);
            if (formDefinitionEntity == null) {
                throw new FlowableObjectNotFoundException("No form definition found for key '" + formDefinitionKey +
                        "' for parent deployment id '" + parentDeploymentId + "' and for tenant identifier " + tenantId, FormDefinitionEntity.class);
            }

        } else {
            throw new FlowableObjectNotFoundException("formDefinitionKey and formDefinitionId are null");
        }

        FormDefinitionCacheEntry formCacheEntry = deploymentManager.resolveFormDefinition(formDefinitionEntity);

        return formCacheEntry;
    }

    protected void fillFormInstanceValues(
            FormInstance formInstance, Map<String, JsonNode> formInstanceFieldMap, ObjectMapper objectMapper) {

        if (formInstance == null) {
            return;
        }

        try {
            JsonNode submittedNode = objectMapper.readTree(formInstance.getFormValueBytes());
            if (submittedNode == null) {
                return;
            }

            if (submittedNode.get("values") != null) {
                JsonNode valuesNode = submittedNode.get("values");
                Iterator<String> fieldIdIterator = valuesNode.fieldNames();
                while (fieldIdIterator.hasNext()) {
                    String fieldId = fieldIdIterator.next();
                    JsonNode valueNode = valuesNode.get(fieldId);
                    formInstanceFieldMap.put(fieldId, valueNode);
                }
            }

        } catch (Exception e) {
            throw new FlowableException("Error parsing form instance " + formInstance.getId(), e);
        }
    }

    public void fillVariablesWithFormInstanceValues(Map<String, JsonNode> formInstanceFieldMap, List<FormField> allFields) {
        for (FormField field : allFields) {

            JsonNode fieldValueNode = formInstanceFieldMap.get(field.getId());

            if (fieldValueNode == null || fieldValueNode.isNull()) {
                continue;
            }

            String fieldType = field.getType();
            String fieldValue = fieldValueNode.asText();

            if (FormFieldTypes.DATE.equals(fieldType)) {
                try {
                    if (StringUtils.isNotEmpty(fieldValue)) {
                        LocalDate dateValue = LocalDate.parse(fieldValue);
                        variables.put(field.getId(), dateValue.toString("yyyy-M-d"));
                    }
                    
                } catch (Exception e) {
                    LOGGER.error("Error parsing form date value for process instance {} with value {}", processInstanceId, fieldValue, e);
                }

            } else {
                variables.put(field.getId(), fieldValue);
            }
        }
    }

    protected FormInstance resolveFormInstance(FormDefinitionCacheEntry formCacheEntry, CommandContext commandContext) {
        if (taskId == null) {
            return null;
        }
        
        List<FormInstance> formInstances = CommandContextUtil.getFormEngineConfiguration().getFormService()
                .createFormInstanceQuery().formDefinitionId(formCacheEntry.getFormDefinitionEntity().getId())
                .taskId(taskId)
                .orderBySubmittedDate()
                .desc()
                .list();

        if (formInstances.size() > 0) {
            return formInstances.get(0);
        }

        return null;
    }

    protected FormModel resolveFormModel(FormDefinitionCacheEntry formCacheEntry, CommandContext commandContext) {
        FormDefinitionEntity formEntity = formCacheEntry.getFormDefinitionEntity();
        FormJsonConverter formJsonConverter = CommandContextUtil.getFormEngineConfiguration().getFormJsonConverter();
        FormModel formDefinition = formJsonConverter.convertToFormModel(formCacheEntry.getFormDefinitionJson(), formEntity.getId(), formEntity.getVersion());
        formDefinition.setId(formEntity.getId());
        formDefinition.setName(formEntity.getName());
        formDefinition.setKey(formEntity.getKey());

        return formDefinition;
    }
}
