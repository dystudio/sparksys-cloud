package com.sparksys.activiti.domain.service.act;

import com.google.common.collect.Lists;
import com.sparksys.activiti.application.service.act.IProcessHistoryService;
import com.sparksys.activiti.application.service.act.IProcessRepositoryService;
import com.sparksys.activiti.application.service.act.IProcessRuntimeService;
import com.sparksys.activiti.application.service.act.IProcessTaskService;
import com.sparksys.activiti.application.service.process.*;
import com.sparksys.activiti.infrastructure.constant.WorkflowConstants;
import com.sparksys.activiti.infrastructure.diagram.CustomProcessDiagramGeneratorImpl;
import com.sparksys.activiti.infrastructure.entity.ActHiTaskStatus;
import com.sparksys.activiti.infrastructure.entity.ProcessHistory;
import com.sparksys.activiti.infrastructure.enums.TaskStatusEnum;
import com.sparksys.activiti.infrastructure.utils.CloseableUtils;
import com.sparksys.core.utils.ListUtils;
import lombok.extern.slf4j.Slf4j;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.FlowNode;
import org.activiti.bpmn.model.SequenceFlow;
import org.activiti.engine.HistoryService;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.runtime.Execution;
import org.activiti.engine.task.Comment;
import org.activiti.engine.task.TaskInfo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * description: 历史流程 服务实现类
 *
 * @author: zhouxinlei
 * @date: 2020-07-17 15:21:22
 */
@Service
@Slf4j
public class ProcessHistoryServiceImpl implements IProcessHistoryService {

    private static final String PNG = "image/png";

    @Autowired
    private HistoryService historyService;

    @Autowired
    private IProcessRepositoryService processRepositoryService;

    @Autowired
    private IProcessTaskService processTaskService;

    @Autowired
    private IProcessRuntimeService processRuntimeService;

    @Autowired
    private IActHiTaskStatusService actHiTaskStatusService;

    @Autowired
    private CustomProcessDiagramGeneratorImpl processDiagramGenerator;

    @Override
    public HistoricProcessInstance getHistoricProcessInstance(String processInstanceId) {
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
    }

    @Override
    public List<HistoricTaskInstance> getHistoricTasksByAssigneeId(String assignee) {
        return historyService.createHistoricTaskInstanceQuery().taskAssignee(assignee).list();
    }

    @Override
    public List<HistoricTaskInstance> getHistoricTasksByProcessInstanceId(String processInstanceId) {
        return historyService.createHistoricTaskInstanceQuery().processInstanceId(processInstanceId).orderByTaskId().asc().list();
    }

    @Override
    public HistoricTaskInstance getHistoricTasksByTaskId(String taskId) {
        return historyService.createHistoricTaskInstanceQuery().taskId(taskId).singleResult();
    }

    @Override
    public List<ProcessHistory> getProcessHistory(String processInstanceId) throws ExecutionException, InterruptedException {

        CompletableFuture<List<ProcessHistory>> hiActInsCompletableFuture =
                CompletableFuture.supplyAsync(() -> buildActivityProcessHistory(processInstanceId));

        CompletableFuture<List<ProcessHistory>> hiTaskInsCompletableFuture =
                CompletableFuture.supplyAsync(() -> buildTaskProcessHistory(processInstanceId));

        CompletableFuture<List<ProcessHistory>> processHistoryCompletableFuture = hiActInsCompletableFuture
                .thenCombine(hiTaskInsCompletableFuture, org.apache.commons.collections4.ListUtils::union);
        List<ProcessHistory> processHistories = processHistoryCompletableFuture.get();
        processHistories.sort(Comparator.comparing(ProcessHistory::getStartTime));
        return processHistories;
    }


    private List<ProcessHistory> buildTaskProcessHistory(String processInstanceId) {
        List<ProcessHistory> processHistories = Lists.newArrayList();
        try {
            // 异步获取历史任务状态
            CompletableFuture<List<ActHiTaskStatus>> hiTaskStatusCompletableFuture =
                    CompletableFuture.supplyAsync(() -> actHiTaskStatusService.getProcessHistory(processInstanceId));
            CompletableFuture<List<HistoricTaskInstance>> hiTakInsCompletableFuture =
                    CompletableFuture.supplyAsync(() -> getHistoricTasksByProcessInstanceId(processInstanceId));
            CompletableFuture<List<Comment>> completableFuture =
                    hiTakInsCompletableFuture(processInstanceId).thenCompose(historicTaskInstance -> {
                        List<String> taskIds = historicTaskInstance.stream().map(TaskInfo::getId).collect(Collectors.toList());
                        return hiCommentCompletableFuture(taskIds, "comment");
                    });
            List<ActHiTaskStatus> actHiTaskStatusList = hiTaskStatusCompletableFuture.get();
            List<HistoricTaskInstance> historicTaskInstances = hiTakInsCompletableFuture.get();
            List<Comment> commentList = completableFuture.get();
            historicTaskInstances.forEach(historicTaskInstance -> {
                ProcessHistory processHistory = ProcessHistory.builder()
                        .processInstanceId(processInstanceId)
                        .taskName(historicTaskInstance.getName())
                        .startTime(historicTaskInstance.getStartTime())
                        .endTime(historicTaskInstance.getEndTime())
                        .duration(historicTaskInstance.getDurationInMillis())
                        .assignee(historicTaskInstance.getAssignee())
                        .dueDate(historicTaskInstance.getDueDate())
                        .build();
                Optional<ActHiTaskStatus> actHiTaskStatusOptional =
                        actHiTaskStatusList.stream().filter(item -> StringUtils.equals(historicTaskInstance.getTaskDefinitionKey(),
                                item.getTaskDefKey())).findFirst();
                actHiTaskStatusOptional.ifPresent(value -> processHistory.setTaskStatus(value.getTaskStatus()));
                if (ListUtils.isNotEmpty(commentList)) {
                    processHistory.setComment(commentList.stream().filter(item -> historicTaskInstance.getId().equals(item.getTaskId())).map(Comment::getFullMessage).collect(Collectors.toList()));
                }
                processHistories.add(processHistory);
            });
        } catch (Exception e) {
            log.error("查询任务历史发生异常 Exception {}", e.getMessage());
        }
        return processHistories;
    }

    public CompletableFuture<List<HistoricTaskInstance>> hiTakInsCompletableFuture(String processInstanceId) {
        return CompletableFuture.supplyAsync(() -> getHistoricTasksByProcessInstanceId(processInstanceId));
    }

    public CompletableFuture<List<Comment>> hiCommentCompletableFuture(List<String> taskIds, String type) {
        return CompletableFuture.supplyAsync(() -> processTaskService.getTaskComments(taskIds, type));
    }

    private List<ProcessHistory> buildActivityProcessHistory(String processInstanceId) {
        List<ProcessHistory> processHistories = Lists.newArrayList();
        List<HistoricActivityInstance> historicActivityInstances = getHistoricActivityInstance(processInstanceId);
        List<HistoricActivityInstance> specialHistoricActivityInstances =
                historicActivityInstances.stream().filter(item -> WorkflowConstants.ActType.START_EVENT.equals(item.getActivityType())
                        || WorkflowConstants.ActType.END_EVENT.equals(item.getActivityType()))
                        .collect(Collectors.toList());
        specialHistoricActivityInstances.forEach(historicActivityInstance -> {
            ProcessHistory processHistory = ProcessHistory.builder()
                    .processInstanceId(processInstanceId)
                    .startTime(historicActivityInstance.getStartTime())
                    .endTime(historicActivityInstance.getEndTime())
                    .duration(historicActivityInstance.getDurationInMillis())
                    .assignee(historicActivityInstance.getAssignee())
                    .build();
            if (WorkflowConstants.ActType.START_EVENT.equals(historicActivityInstance.getActivityType())) {
                processHistory.setTaskStatus(TaskStatusEnum.START.getDesc());
                processHistory.setTaskName("启动流程");
            }
            if (WorkflowConstants.ActType.END_EVENT.equals(historicActivityInstance.getActivityType())) {
                processHistory.setTaskStatus(TaskStatusEnum.END.getDesc());
                processHistory.setTaskName("完成流程");
            }
            processHistories.add(processHistory);
        });
        return processHistories;
    }

    public List<HistoricActivityInstance> getHistoricActivityInstance(String processInstanceId) {
        return historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceId().asc().list();
    }

    @Override
    public void getProcessImage(String processInstanceId, HttpServletResponse response) {
        InputStream imageStream = null;
        ServletOutputStream outputStream = null;
        try {
            if (StringUtils.isBlank(processInstanceId)) {
                log.error("参数为空");
            }
            // 获取历史流程实例
            HistoricProcessInstance processInstance = getHistoricProcessInstance(processInstanceId);

            // 获取流程定义ID
            String processDefinitionId = processInstance.getProcessDefinitionId();

            // 获取流程定义信息
            BpmnModel bpmnModel = processRepositoryService.getBpmnModel(processDefinitionId);

            // 获取流程历史中已执行节点
            List<HistoricActivityInstance> historicActivityInstance = getHistoricActivityInstance(processInstanceId);

            // 高亮环节id集合
            List<String> highLightedActivitis = new ArrayList<>();
            for (HistoricActivityInstance tempActivity : historicActivityInstance) {
                String activityId = tempActivity.getActivityId();
                highLightedActivitis.add(activityId);
            }

            // 高亮线路id集合
            List<String> highLightedFlows = getHighLightedFlows(bpmnModel, historicActivityInstance);

            Set<String> currIds =
                    processRuntimeService.getExecutionByProcInsId(processInstanceId).stream().map(Execution::getActivityId).collect(Collectors.toSet());

            imageStream = processDiagramGenerator.generateDiagram(bpmnModel, "png", highLightedActivitis,
                    highLightedFlows, "宋体", "宋体", "宋体",
                    null, 1.0,
                    new Color[]{WorkflowConstants.COLOR_NORMAL, WorkflowConstants.COLOR_CURRENT}, currIds);
            // 设定输出的类型
            response.setContentType(PNG);
            // 输出流程图
            outputStream = response.getOutputStream();
            byte[] b = new byte[2048];
            int len;
            while ((len = imageStream.read(b, 0, b.length)) != -1) {
                outputStream.write(b, 0, len);
            }
        } catch (IOException e) {
            throw new RuntimeException("获取流程图出错", e);
        } finally {
            CloseableUtils.close(outputStream, imageStream);
        }
    }

    private List<String> getHighLightedFlows(BpmnModel bpmnModel, List<HistoricActivityInstance> historicActivityInstances) {
        // 24小时制
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        // 用以保存高亮的线flowId
        List<String> highFlows = new ArrayList<String>();

        for (int i = 0; i < historicActivityInstances.size() - 1; i++) {
            // 对历史流程节点进行遍历
            // 得到节点定义的详细信息
            FlowNode activityImpl = (FlowNode) bpmnModel.getMainProcess().getFlowElement(historicActivityInstances.get(i).getActivityId());
            // 用以保存后续开始时间相同的节点
            List<FlowNode> sameStartTimeNodes = new ArrayList<FlowNode>();
            FlowNode sameActivityImpl1 = null;
            // 第一个节点
            HistoricActivityInstance activityInstance = historicActivityInstances.get(i);
            HistoricActivityInstance activityInstance1;

            for (int k = i + 1; k <= historicActivityInstances.size() - 1; k++) {
                // 后续第1个节点
                activityInstance1 = historicActivityInstances.get(k);

                // 都是usertask，且主节点与后续节点的开始时间相同，说明不是真实的后继节点
                if ("userTask".equals(activityInstance.getActivityType()) && "userTask".equals(activityInstance1.getActivityType()) &&
                        df.format(activityInstance.getStartTime()).equals(df.format(activityInstance1.getStartTime()))) {

                } else {
                    // 找到紧跟在后面的一个节点
                    sameActivityImpl1 = (FlowNode) bpmnModel.getMainProcess().getFlowElement(historicActivityInstances.get(k).getActivityId());
                    break;
                }
            }
            // 将后面第一个节点放在时间相同节点的集合里
            sameStartTimeNodes.add(sameActivityImpl1);
            for (int j = i + 1; j < historicActivityInstances.size() - 1; j++) {
                // 后续第一个节点
                HistoricActivityInstance activityImpl1 = historicActivityInstances.get(j);
                // 后续第二个节点
                HistoricActivityInstance activityImpl2 = historicActivityInstances.get(j + 1);

                // 如果第一个节点和第二个节点开始时间相同保存
                if (df.format(activityImpl1.getStartTime()).equals(df.format(activityImpl2.getStartTime()))) {
                    FlowNode sameActivityImpl2 = (FlowNode) bpmnModel.getMainProcess().getFlowElement(activityImpl2.getActivityId());
                    sameStartTimeNodes.add(sameActivityImpl2);
                } else {
                    // 有不相同跳出循环
                    break;
                }
            }
            // 取出节点的所有出去的线
            List<SequenceFlow> pvmTransitions = activityImpl.getOutgoingFlows();

            // 对所有的线进行遍历
            for (SequenceFlow pvmTransition : pvmTransitions) {
                // 如果取出的线的目标节点存在时间相同的节点里，保存该线的id，进行高亮显示
                FlowNode pvmActivityImpl = (FlowNode) bpmnModel.getMainProcess().getFlowElement(pvmTransition.getTargetRef());
                if (sameStartTimeNodes.contains(pvmActivityImpl)) {
                    highFlows.add(pvmTransition.getId());
                }
            }
        }
        return highFlows;
    }
}
