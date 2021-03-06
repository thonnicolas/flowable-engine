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
package org.flowable.engine.impl.agenda;

import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.engine.common.api.FlowableException;
import org.flowable.engine.common.impl.interceptor.CommandContext;
import org.flowable.engine.common.impl.util.CollectionUtil;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.delegate.event.FlowableEngineEventType;
import org.flowable.engine.delegate.event.impl.FlowableEventBuilder;
import org.flowable.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior;
import org.flowable.engine.impl.bpmn.helper.ErrorPropagation;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.delegate.ActivityBehavior;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.persistence.entity.JobEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.logging.LogMDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Special operation when executing an instance of a multi-instance. It's similar to the {@link ContinueProcessOperation}, but simpler, as it doesn't need to cater for as many use cases.
 * 
 * @author Joram Barrez
 * @author Tijs Rademakers
 */
public class ContinueMultiInstanceOperation extends AbstractOperation {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContinueMultiInstanceOperation.class);
    
    protected int loopCounter;

    public ContinueMultiInstanceOperation(CommandContext commandContext, ExecutionEntity execution, int loopCounter) {
        super(commandContext, execution);
        this.loopCounter = loopCounter;
    }

    @Override
    public void run() {
        FlowElement currentFlowElement = getCurrentFlowElement(execution);
        if (currentFlowElement instanceof FlowNode) {
            continueThroughMultiInstanceFlowNode((FlowNode) currentFlowElement);
        } else {
            throw new RuntimeException("Programmatic error: no valid multi instance flow node, type: " + currentFlowElement + ". Halting.");
        }
    }

    protected void continueThroughMultiInstanceFlowNode(FlowNode flowNode) {
        setLoopCounterVariable(flowNode);
        if (!flowNode.isAsynchronous()) {
            executeSynchronous(flowNode);
        } else {
            executeAsynchronous(flowNode);
        }
    }

    protected void executeSynchronous(FlowNode flowNode) {
        
        CommandContextUtil.getHistoryManager(commandContext).recordActivityStart(execution);
        
        // Execution listener
        if (CollectionUtil.isNotEmpty(flowNode.getExecutionListeners())) {
            executeExecutionListeners(flowNode, ExecutionListener.EVENTNAME_START);
        }
        
        // Execute actual behavior
        ActivityBehavior activityBehavior = (ActivityBehavior) flowNode.getBehavior();
        LOGGER.debug("Executing activityBehavior {} on activity '{}' with execution {}", activityBehavior.getClass(), flowNode.getId(), execution.getId());

        ProcessEngineConfigurationImpl processEngineConfiguration = CommandContextUtil.getProcessEngineConfiguration(commandContext);
        if (processEngineConfiguration != null && processEngineConfiguration.getEventDispatcher().isEnabled()) {
            processEngineConfiguration.getEventDispatcher().dispatchEvent(
                    FlowableEventBuilder.createActivityEvent(FlowableEngineEventType.ACTIVITY_STARTED, flowNode.getId(), flowNode.getName(), execution.getId(),
                            execution.getProcessInstanceId(), execution.getProcessDefinitionId(), flowNode));
        }

        try {
            activityBehavior.execute(execution);
        } catch (BpmnError error) {
            // re-throw business fault so that it can be caught by an Error Intermediate Event or Error Event Sub-Process in the process
            ErrorPropagation.propagateError(error, execution);
        } catch (RuntimeException e) {
            if (LogMDC.isMDCEnabled()) {
                LogMDC.putMDCExecution(execution);
            }
            throw e;
        }
    }

    protected void executeAsynchronous(FlowNode flowNode) {
        JobEntity job = CommandContextUtil.getJobManager(commandContext).createAsyncJob(execution, flowNode.isExclusive());
        CommandContextUtil.getJobManager(commandContext).scheduleAsyncJob(job);
    }
    
    protected ActivityBehavior setLoopCounterVariable(FlowNode flowNode) {
        ActivityBehavior activityBehavior = (ActivityBehavior) flowNode.getBehavior();
        if (!(activityBehavior instanceof MultiInstanceActivityBehavior)) {
            throw new FlowableException("Programmatic error: expected multi instance activity behavior, but got " + activityBehavior.getClass());
        }
        MultiInstanceActivityBehavior multiInstanceActivityBehavior = (MultiInstanceActivityBehavior) activityBehavior;
        String elementIndexVariable = multiInstanceActivityBehavior.getCollectionElementIndexVariable();
        execution.setVariableLocal(elementIndexVariable, loopCounter);
        return activityBehavior;
    }
    
}
