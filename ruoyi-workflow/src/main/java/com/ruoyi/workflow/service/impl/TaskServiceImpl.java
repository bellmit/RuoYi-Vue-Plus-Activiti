package com.ruoyi.workflow.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.helper.LoginHelper;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.workflow.activiti.cmd.JumpAnyWhereCmd;
import com.ruoyi.workflow.common.constant.ActConstant;
import com.ruoyi.workflow.common.enums.BusinessStatusEnum;
import com.ruoyi.workflow.domain.ActHiTaskInst;
import com.ruoyi.workflow.domain.ActNodeAssignee;
import com.ruoyi.workflow.domain.ActRuExecution;
import com.ruoyi.workflow.domain.ActTaskNode;
import com.ruoyi.workflow.domain.bo.NextNodeREQ;
import com.ruoyi.workflow.domain.bo.TaskCompleteREQ;
import com.ruoyi.workflow.domain.bo.TaskREQ;
import com.ruoyi.workflow.domain.vo.*;
import com.ruoyi.workflow.factory.WorkflowService;
import com.ruoyi.workflow.service.*;
import com.ruoyi.workflow.utils.WorkFlowUtils;
import lombok.extern.slf4j.Slf4j;
import org.activiti.bpmn.model.*;
import org.activiti.engine.ManagementService;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.history.HistoricTaskInstanceQuery;
import org.activiti.engine.impl.persistence.entity.ExecutionEntityImpl;
import org.activiti.engine.impl.persistence.entity.TaskEntity;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.activiti.engine.task.TaskQuery;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.ruoyi.common.helper.LoginHelper.getUserId;

/**
 * @program: ruoyi-vue-plus
 * @description: ???????????????
 * @author: gssong
 * @created: 2021/10/17 14:57
 */
@Service
@Slf4j
public class TaskServiceImpl extends WorkflowService implements ITaskService {

    @Autowired
    private ISysUserService iSysUserService;

    @Autowired
    private IActBusinessStatusService iActBusinessStatusService;

    @Autowired
    private WorkFlowUtils workFlowUtils;

    @Autowired
    private IActTaskNodeService iActTaskNodeService;

    @Autowired
    private IActNodeAssigneeService iActNodeAssigneeService;

    @Autowired
    private IActFullClassService iActFullClassService;

    @Autowired
    private IActHiActInstService iActHiActInstService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private IActRuExecutionService iActRuExecutionService;

    @Autowired
    private IActHiTaskInstService iActHiTaskInstService;



    /**
     * ?????????????????????????????????
     * @param req
     * @return
     */
    @Override
    public TableDataInfo<TaskWaitingVo> getTaskWaitByPage(TaskREQ req) {
        //???????????????
        String username = LoginHelper.getLoginUser().getUserId().toString();
        TaskQuery query = taskService.createTaskQuery()
            .taskCandidateOrAssigned(String.valueOf(username)) // ????????????????????????
            .orderByTaskCreateTime().asc();
        if (StringUtils.isNotEmpty(req.getTaskName())) {
            query.taskNameLikeIgnoreCase("%" + req.getTaskName() + "%");
        }
        List<Task> taskList = query.listPage(req.getFirstResult(), req.getPageSize());
        long total = query.count();
        List<TaskWaitingVo> list = new ArrayList<>();
        for (Task task : taskList) {
            TaskWaitingVo taskWaitingVo = new TaskWaitingVo();
            BeanUtils.copyProperties(task, taskWaitingVo);
            taskWaitingVo.setProcessStatus(task.isSuspended() == true ? "??????" : "??????");
            //???????????????: ????????????????????????????????????????????????
            if (StringUtils.isNotBlank(taskWaitingVo.getAssignee())) {
                String[] split = taskWaitingVo.getAssignee().split(",");
                List<Long> userIds = new ArrayList<>();
                for (String userId : split) {
                    userIds.add(Long.valueOf(userId));
                }
                List<SysUser> userList = iSysUserService.selectListUserByIds(userIds);
                if (CollectionUtil.isNotEmpty(userList)) {
                    List<String> nickNames = userList.stream().map(SysUser::getNickName).collect(Collectors.toList());
                    taskWaitingVo.setAssignee(StringUtils.join(nickNames, ","));
                    taskWaitingVo.setAssigneeId(StringUtils.join(userIds, ","));
                }

            }
            // ??????????????????
            ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).singleResult();
            //???????????????
            String startUserId = pi.getStartUserId();
            if (StringUtils.isNotBlank(startUserId)) {
                SysUser sysUser = iSysUserService.selectUserById(Long.valueOf(startUserId));
                if (ObjectUtil.isNotNull(sysUser)) {
                    taskWaitingVo.setStartUserNickName(sysUser.getNickName());
                }
            }
            taskWaitingVo.setProcessDefinitionVersion(pi.getProcessDefinitionVersion());
            taskWaitingVo.setProcessDefinitionName(pi.getProcessDefinitionName());
            taskWaitingVo.setBusinessKey(pi.getBusinessKey());
            list.add(taskWaitingVo);
        }
        return new TableDataInfo(list, total);
    }

    /**
     * ????????????
     * @param req
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean completeTask(TaskCompleteREQ req) {
        // 1.????????????
        Task task = taskService.createTaskQuery().taskId(req.getTaskId()).taskAssignee(getUserId().toString()).singleResult();

        if (ObjectUtil.isNull(task)) {
            throw new ServiceException("??????????????????????????????????????????");
        }

        if (task.isSuspended()) {
            throw new ServiceException("????????????????????????");
        }
        //??????????????????
        if(ObjectUtil.isNotEmpty(task.getDelegationState())&&ActConstant.PENDING.equals(task.getDelegationState().name())){
            taskService.addComment(task.getId(),task.getProcessInstanceId(),req.getMessage());
            taskService.resolveTask(req.getTaskId());
            ActHiTaskInst hiTaskInst = iActHiTaskInstService.getById(task.getId());
            TaskEntity subTask = createSubTask(task, hiTaskInst.getStartTime());
            taskService.addComment(subTask.getId(), task.getProcessInstanceId(), req.getMessage());
            taskService.complete(subTask.getId());
            ActHiTaskInst actHiTaskInst = new ActHiTaskInst();
            actHiTaskInst.setId(task.getId());
            actHiTaskInst.setStartTime(new Date());
            iActHiTaskInstService.updateById(actHiTaskInst);
            return true;
        }
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(task.getProcessInstanceId()).singleResult();
        //????????????????????????????????? ??????????????? ????????????????????????????????????
        List<ActNodeAssignee> actNodeAssignees = iActNodeAssigneeService.getInfoByProcessDefinitionId(task.getProcessDefinitionId());
        for (ActNodeAssignee actNodeAssignee : actNodeAssignees) {
            String column = actNodeAssignee.getMultipleColumn();
            String assigneeId = actNodeAssignee.getAssigneeId();
            if(actNodeAssignee.getMultiple()&&actNodeAssignee.getIsShow()){
                List<Long> userIdList = req.getAssignees(actNodeAssignee.getMultipleColumn());
                if(CollectionUtil.isNotEmpty(userIdList)){
                    taskService.setVariable(task.getId(),column,userIdList);
                }
            }
            //?????????????????????????????????????????????????????????
            if(actNodeAssignee.getMultiple()&&!actNodeAssignee.getIsShow()&&(StringUtils.isBlank(column) || StringUtils.isBlank(assigneeId))){
                throw new ServiceException("????????????"+processInstance.getProcessDefinitionKey()+"????????? ");
            }
            if(actNodeAssignee.getMultiple()&&!actNodeAssignee.getIsShow()){
                List<Long> userIds = new ArrayList<>();
                String[] split = assigneeId.split(",");
                for (String userId : split) {
                    userIds.add(Long.valueOf(userId));
                }
                taskService.setVariable(task.getId(),actNodeAssignee.getMultipleColumn(),userIds);
            }
        }
        // 3. ????????????????????????
        taskService.addComment(req.getTaskId(), task.getProcessInstanceId(), req.getMessage());
        // 4. ????????????
        taskService.setVariables(req.getTaskId(), req.getVariables());
        taskService.complete(req.getTaskId());
        // 5. ??????????????????????????????
        List<ActTaskNode> actTaskNodeList = iActTaskNodeService.getListByInstanceId(task.getProcessInstanceId());
        List<String> nodeIdList = actTaskNodeList.stream().map(ActTaskNode::getNodeId).collect(Collectors.toList());
        if (!nodeIdList.contains(task.getTaskDefinitionKey())) {
            ActTaskNode actTaskNode = new ActTaskNode();
            actTaskNode.setNodeId(task.getTaskDefinitionKey());
            actTaskNode.setNodeName(task.getName());
            actTaskNode.setInstanceId(task.getProcessInstanceId());
            if (CollectionUtil.isEmpty(actTaskNodeList)) {
                actTaskNode.setOrderNo(0);
                actTaskNode.setIsBack(true);
            } else {
                ActNodeAssignee actNodeAssignee = actNodeAssignees.stream().filter(e -> e.getNodeId().equals(task.getTaskDefinitionKey())).findFirst().orElse(null);
                //??????????????????????????????????????? ????????????????????????
                if(ObjectUtil.isEmpty(actNodeAssignee)){
                    actTaskNode.setIsBack(true);
                    actTaskNode.setOrderNo(actTaskNodeList.get(0).getOrderNo() + 1);
                }else{
                    actTaskNode.setIsBack(actNodeAssignee.getIsBack());
                    actTaskNode.setOrderNo(actTaskNodeList.get(0).getOrderNo() + 1);
                }
            }
            iActTaskNodeService.save(actTaskNode);
        }
        // ?????????????????????????????????, ???????????????id
        iActBusinessStatusService.updateState(processInstance.getBusinessKey(), BusinessStatusEnum.WAITING, task.getProcessInstanceId());
        // 6. ?????????????????????
        List<Task> taskList = taskService.createTaskQuery().processInstanceId(task.getProcessInstanceId()).list();
        // 7. ???????????? ????????????
        if (CollectionUtil.isEmpty(taskList)) {
            HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).singleResult();
            // ??????????????????????????? ????????????
            boolean b = iActBusinessStatusService.updateState(hpi.getBusinessKey(), BusinessStatusEnum.FINISH);
            return b;
        }

        // 8. ??????????????? ???????????????
        if (CollectionUtil.isNotEmpty(taskList)) {
            // 9. ???????????????
            for (Task t : taskList) {
                ActNodeAssignee nodeAssignee = actNodeAssignees.stream().filter(e -> t.getTaskDefinitionKey().equals(e.getNodeId())).findFirst().orElse(null);
                if(ObjectUtil.isNotNull(nodeAssignee)){
                    // ?????????????????????
                    if(!nodeAssignee.getIsShow()){
                        //??????????????????
                        if(ActConstant.WORKFLOW_RULE.equals(nodeAssignee.getChooseWay())){
                            ActFullClassVo actFullClassVo = iActFullClassService.queryById(nodeAssignee.getFullClassId());
                            //?????????????????????
                            Object assignee = workFlowUtils.assignList(actFullClassVo, t.getId());
                            List<Long> userIds = new ArrayList<>();
                            String[] splitUserIds = assignee.toString().split(",");
                            for (String userId : splitUserIds) {
                                userIds.add(Long.valueOf(userId));
                            }
                            List<SysUser> userList = iSysUserService.selectListUserByIds(userIds);
                            if (CollectionUtil.isEmpty(userList)) {
                                throw new ServiceException("???" + t.getName() + "?????????????????????????????????");
                            }
                            settingAssignee(t,userList.stream().map(SysUser::getUserId).collect(Collectors.toList()));
                        }else{
                            // ??????????????????
                            List<Long> assignees = workFlowUtils.assignees(nodeAssignee.getAssigneeId(), nodeAssignee.getChooseWay(), t.getName());
                            settingAssignee(t,assignees);
                        }
                    }else{
                        //???????????? ????????????????????????id???????????????
                        if(t.getTaskDefinitionKey().equals(nodeAssignee.getNodeId()) && nodeAssignee.getIsShow()){
                            List<Long> assignees = req.getAssignees(t.getTaskDefinitionKey());
                            //????????????
                            if (CollectionUtil.isNotEmpty(assignees)) {
                                settingAssignee(t,assignees);
                            } else if (StringUtils.isBlank(t.getAssignee())) {
                                if(taskList.size()==1){
                                    throw new ServiceException("???" + t.getName() + "?????????????????????????????????");
                                }
                            }
                        }else{
                            //??????????????????
                            ActNodeAssignee info = iActNodeAssigneeService.getInfo(t.getProcessDefinitionId(), t.getTaskDefinitionKey());
                            if(ObjectUtil.isNotNull(info)&&!info.getMultiple()){
                                throw new ServiceException("???" + t.getName() + "?????????????????????????????????");
                            }else{
                                List<ActNodeAssignee> list = iActNodeAssigneeService.getInfoByProcessDefinitionId(task.getProcessDefinitionId());
                                for (ActNodeAssignee actNodeAssignee : list) {
                                    if(actNodeAssignee.getMultiple()&&StringUtils.isNotBlank(actNodeAssignee.getMultipleColumn())){
                                        Object variable = runtimeService.getVariable(t.getExecutionId(), actNodeAssignee.getMultipleColumn());
                                        if(ObjectUtil.isEmpty(variable)){
                                            throw new ServiceException("???" + t.getName() + "?????????????????????????????????");
                                        }
                                    }
                                    if(actNodeAssignee.getMultiple()&&StringUtils.isBlank(actNodeAssignee.getMultipleColumn())){
                                        throw new ServiceException("???" + t.getName() + "?????????????????????????????????");
                                    }
                                }
                            }
                        }
                    }
                } else if (StringUtils.isBlank(t.getAssignee())) {
                    throw new ServiceException("???" + t.getName() + "?????????????????????????????????");
                }
            }
        }
        return true;
    }

    /**
     * ????????????????????????
     * @param task
     * @param assignees
     */
    public void settingAssignee(Task task,List<Long> assignees){
        if (assignees.size() == 1) {
            taskService.setAssignee(task.getId(), assignees.get(0).toString());
        } else {
            // ?????????????????????
            for (Long assignee : assignees) {
                taskService.addCandidateUser(task.getId(), assignee.toString());
            }
        }
    }

    /**
     * ?????????????????????????????????
     * @param req
     * @return
     */
    @Override
    public TableDataInfo<TaskFinishVo> getTaskFinishByPage(TaskREQ req) {
        //???????????????
        String username = LoginHelper.getUserId().toString();
        HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
            .taskAssignee(username).finished().orderByHistoricTaskInstanceStartTime().asc();
        if (StringUtils.isNotBlank(req.getTaskName())) {
            query.taskNameLike(req.getTaskName());
        }
        List<HistoricTaskInstance> list = query.listPage(req.getFirstResult(), req.getPageSize());
        long total = query.count();
        List<TaskFinishVo> taskFinishVoList = new ArrayList<>();
        for (HistoricTaskInstance hti : list) {
            TaskFinishVo taskFinishVo = new TaskFinishVo();
            BeanUtils.copyProperties(hti, taskFinishVo);
            ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(hti.getProcessDefinitionId()).singleResult();
            taskFinishVo.setProcessDefinitionName(processDefinition.getName());
            taskFinishVo.setProcessDefinitionKey(processDefinition.getKey());
            taskFinishVo.setVersion(processDefinition.getVersion());
            if (StringUtils.isNotBlank(hti.getAssignee())) {
                String[] split = hti.getAssignee().split(",");
                List<Long> userIds = new ArrayList<>();
                for (String userId : split) {
                    userIds.add(Long.valueOf(userId));
                }
                List<SysUser> userList = iSysUserService.selectListUserByIds(userIds);
                if (CollectionUtil.isNotEmpty(userList)) {
                    List<String> nickNames = userList.stream().map(SysUser::getNickName).collect(Collectors.toList());
                    taskFinishVo.setAssignee(StringUtils.join(nickNames, ","));
                    taskFinishVo.setAssigneeId(StringUtils.join(userIds, ","));
                }

            }
            taskFinishVoList.add(taskFinishVo);
        }
        return new TableDataInfo(taskFinishVoList, total);
    }

    /**
     * ???????????????????????????????????????
     * @param req
     * @return
     */
    @Override
    public List<ProcessNode> getNextNodeInfo(NextNodeREQ req) {
        //????????????
        TaskEntity task = (TaskEntity)taskService.createTaskQuery().taskId(req.getTaskId()).singleResult();
        //????????????
        if(ObjectUtil.isNotEmpty(task.getDelegationState())&&ActConstant.PENDING.equals(task.getDelegationState().name())){
            return null;
        }
        //????????????
        List<Task> taskList = taskService.createTaskQuery().processInstanceId(task.getProcessInstanceId()).list();
        //?????????????????????????????????????????????
        if(CollectionUtil.isNotEmpty(taskList)&&taskList.size()>1){
            //return null;
        }
        taskService.setVariables(task.getId(),req.getVariables());
        //????????????
        String processDefinitionId = task.getProcessDefinitionId();
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(task.getProcessInstanceId()).singleResult();
        //??????id
        String businessKey = processInstance.getBusinessKey();
        //??????bpmn??????
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        //??????????????????id??????????????????????????????
        FlowElement flowElement = bpmnModel.getFlowElement(task.getTaskDefinitionKey());
        // ???????????????????????????????????????
        List<ProcessNode> nextNodes = new ArrayList<>();
        //  ??????????????????????????????
        List<ProcessNode> tempNodes = new ArrayList<>();
        ExecutionEntityImpl executionEntity = (ExecutionEntityImpl) runtimeService.createExecutionQuery()
            .executionId(task.getExecutionId()).singleResult();
        workFlowUtils.getNextNodes(flowElement,executionEntity, nextNodes, tempNodes, task.getId(), businessKey, null);

        //????????????  ??????????????????????????????????????????false  ?????????????????????????????????????????????
        List<String> exclusiveLists = nextNodes.stream().filter(e -> e.getNodeType().equals(ActConstant.EXCLUSIVEGATEWAY) && e.getExpression().equals(ActConstant.TRUE)).
            map(ProcessNode::getNodeType).collect(Collectors.toList());
        if (CollectionUtil.isEmpty(nextNodes) && CollectionUtil.isEmpty(exclusiveLists)) {
            nextNodes.addAll(tempNodes);
        }
        // ????????????
        List<ProcessNode> nodeList = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(nextNodes)) {
            //????????????  ??????????????????????????????????????????false  ?????????????????????????????????????????????
            List<String> exclusiveList = nextNodes.stream().filter(e -> e.getNodeType().equals(ActConstant.EXCLUSIVEGATEWAY) && e.getExpression().equals(ActConstant.TRUE)).
                map(ProcessNode::getNodeType).collect(Collectors.toList());
            if (!CollectionUtil.isEmpty(nextNodes) && CollectionUtil.isEmpty(exclusiveList)) {
                nextNodes.addAll(tempNodes);
            }
            //????????????
            List<String> exclusiveGatewayList = nextNodes.stream().filter(e -> e.getNodeType().equals(ActConstant.EXCLUSIVEGATEWAY) && e.getExpression().equals(ActConstant.TRUE)).
                map(ProcessNode::getNodeType).collect(Collectors.toList());
            if (CollectionUtil.isNotEmpty(exclusiveGatewayList)) {
                nextNodes.forEach(node -> {
                    if ((ActConstant.EXCLUSIVEGATEWAY.equals(node.getNodeType()) && node.getExpression())) {
                        nodeList.add(node);
                    }
                });
                //????????????????????????
                List<ProcessNode> processNodeList = getProcessNodeAssigneeList(nodeList, task.getProcessDefinitionId());
                return processNodeList;
            } else {
                //????????????????????????
                List<ProcessNode> processNodeList = getProcessNodeAssigneeList(nextNodes, task.getProcessDefinitionId());
                return processNodeList;
            }
        }
        return nextNodes;
    }

    /**
     * ????????????????????????
     * @param nodeList
     * @param definitionId
     * @return
     */
    private List<ProcessNode> getProcessNodeAssigneeList(List<ProcessNode> nodeList, String definitionId) {
        List<ActNodeAssignee> actNodeAssignees = iActNodeAssigneeService.getInfoByProcessDefinitionId(definitionId);
        if (CollectionUtil.isNotEmpty(actNodeAssignees)) {
            for (ProcessNode processNode : nodeList) {
                //??????????????????????????????????????????????????????
                if (StringUtils.isBlank(processNode.getAssignee())) {
                    if(CollectionUtil.isEmpty(actNodeAssignees)){
                        throw new ServiceException("????????????????????????????????????????????????");
                    }
                    ActNodeAssignee nodeAssignee = actNodeAssignees.stream().filter(e -> e.getNodeId().equals(processNode.getNodeId())).findFirst().orElse(null);

                    //????????? ?????? ??????id ???????????????????????????
                    if (ObjectUtil.isNotNull(nodeAssignee) && StringUtils.isNotBlank(nodeAssignee.getAssigneeId())
                        && nodeAssignee.getFullClassId() == null && StringUtils.isNotBlank(nodeAssignee.getAssignee())) {
                        processNode.setChooseWay(nodeAssignee.getChooseWay());
                        processNode.setAssignee(nodeAssignee.getAssignee());
                        processNode.setAssigneeId(nodeAssignee.getAssigneeId());
                        processNode.setIsShow(nodeAssignee.getIsShow());
                        if(nodeAssignee.getMultiple()){
                            processNode.setNodeId(nodeAssignee.getMultipleColumn());
                        }
                        processNode.setMultiple(nodeAssignee.getMultiple());
                        processNode.setMultipleColumn(nodeAssignee.getMultipleColumn());
                        //??????????????????????????????????????????
                    } else if (ObjectUtil.isNotNull(nodeAssignee) && nodeAssignee.getFullClassId() != null) {
                        ActFullClassVo actFullClassVo = iActFullClassService.queryById(nodeAssignee.getFullClassId());
                        Object assignee = workFlowUtils.assignList(actFullClassVo, processNode.getTaskId());
                        processNode.setChooseWay(nodeAssignee.getChooseWay());
                        processNode.setAssignee("");
                        processNode.setAssigneeId(assignee.toString());
                        processNode.setIsShow(nodeAssignee.getIsShow());
                        if(nodeAssignee.getMultiple()){
                            processNode.setNodeId(nodeAssignee.getMultipleColumn());
                        }
                        processNode.setMultiple(nodeAssignee.getMultiple());
                        processNode.setMultipleColumn(nodeAssignee.getMultipleColumn());
                    }else{
                        throw new ServiceException(processNode.getNodeName() + "??????????????????????????????????????????");
                    }
                } else {
                    ActNodeAssignee nodeAssignee = actNodeAssignees.stream().filter(e -> e.getNodeId().equals(processNode.getNodeId())).findFirst().orElse(null);
                    if(ObjectUtil.isNotEmpty(nodeAssignee)){
                        processNode.setChooseWay(nodeAssignee.getChooseWay());
                        processNode.setAssignee(nodeAssignee.getAssignee());
                        processNode.setAssigneeId(nodeAssignee.getAssigneeId());
                        processNode.setIsShow(nodeAssignee.getIsShow());
                        if(nodeAssignee.getMultiple()){
                            processNode.setNodeId(nodeAssignee.getMultipleColumn());
                        }
                        processNode.setMultiple(nodeAssignee.getMultiple());
                        processNode.setMultipleColumn(nodeAssignee.getMultipleColumn());
                    }else{
                        processNode.setChooseWay(ActConstant.WORKFLOW_ASSIGNEE);
                    }
                }
            }
        }
        if (CollectionUtil.isNotEmpty(nodeList)) {
            Iterator<ProcessNode> iterator = nodeList.iterator();
            while (iterator.hasNext()) {
                ProcessNode node = iterator.next();
                // ???????????????????????????????????????  ??????????????? ??????
                // ????????????????????????????????????
                if (ActConstant.WORKFLOW_ASSIGNEE.equals(node.getChooseWay())||!node.getIsShow()) {
                    iterator.remove();
                }
            }
        }
        return nodeList;
    }

    /**
     * ?????????????????????????????????
     * @param req
     * @return
     */
    @Override
    public TableDataInfo<TaskFinishVo> getAllTaskFinishByPage(TaskREQ req) {
        HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
            .finished().orderByHistoricTaskInstanceStartTime().asc();
        if (StringUtils.isNotBlank(req.getTaskName())) {
            query.taskNameLike(req.getTaskName());
        }
        List<HistoricTaskInstance> list = query.listPage(req.getFirstResult(), req.getPageSize());
        long total = query.count();
        List<TaskFinishVo> taskFinishVoList = new ArrayList<>();
        for (HistoricTaskInstance hti : list) {
            TaskFinishVo taskFinishVo = new TaskFinishVo();
            BeanUtils.copyProperties(hti, taskFinishVo);
            ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(hti.getProcessDefinitionId()).singleResult();
            taskFinishVo.setProcessDefinitionName(processDefinition.getName());
            taskFinishVo.setProcessDefinitionKey(processDefinition.getKey());
            taskFinishVo.setVersion(processDefinition.getVersion());
            if (StringUtils.isNotBlank(hti.getAssignee())) {
                String[] split = hti.getAssignee().split(",");
                List<Long> userIds = new ArrayList<>();
                for (String userId : split) {
                    userIds.add(Long.valueOf(userId));
                }
                List<SysUser> userList = iSysUserService.selectListUserByIds(userIds);
                if (CollectionUtil.isNotEmpty(userList)) {
                    List<String> nickNames = userList.stream().map(SysUser::getNickName).collect(Collectors.toList());
                    taskFinishVo.setAssignee(StringUtils.join(nickNames, ","));
                    taskFinishVo.setAssigneeId(StringUtils.join(userIds, ","));
                }

            }
            taskFinishVoList.add(taskFinishVo);
        }
        return new TableDataInfo(taskFinishVoList, total);
    }

    /**
     * ?????????????????????????????????
     * @param req
     * @return
     */
    @Override
    public TableDataInfo<TaskWaitingVo> getAllTaskWaitByPage(TaskREQ req) {
        TaskQuery query = taskService.createTaskQuery()
            .orderByTaskCreateTime().asc();
        if (StringUtils.isNotEmpty(req.getTaskName())) {
            query.taskNameLikeIgnoreCase("%" + req.getTaskName() + "%");
        }
        List<Task> taskList = query.listPage(req.getFirstResult(), req.getPageSize());
        long total = query.count();
        List<TaskWaitingVo> list = new ArrayList<>();
        for (Task task : taskList) {
            TaskWaitingVo taskWaitingVo = new TaskWaitingVo();
            BeanUtils.copyProperties(task, taskWaitingVo);
            taskWaitingVo.setProcessStatus(task.isSuspended() == true ? "??????" : "??????");
            //???????????????: ????????????????????????????????????????????????
            if (StringUtils.isNotBlank(taskWaitingVo.getAssignee())) {
                String[] split = taskWaitingVo.getAssignee().split(",");
                List<Long> userIds = new ArrayList<>();
                for (String userId : split) {
                    userIds.add(Long.valueOf(userId));
                }
                List<SysUser> userList = iSysUserService.selectListUserByIds(userIds);
                if (CollectionUtil.isNotEmpty(userList)) {
                    List<String> nickNames = userList.stream().map(SysUser::getNickName).collect(Collectors.toList());
                    taskWaitingVo.setAssignee(StringUtils.join(nickNames, ","));
                    taskWaitingVo.setAssigneeId(StringUtils.join(userIds, ","));
                }

            }
            // ??????????????????
            ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).singleResult();
            //???????????????
            String startUserId = pi.getStartUserId();
            if (StringUtils.isNotBlank(startUserId)) {
                SysUser sysUser = iSysUserService.selectUserById(Long.valueOf(startUserId));
                if (ObjectUtil.isNotNull(sysUser)) {
                    taskWaitingVo.setStartUserNickName(sysUser.getNickName());
                }
            }
            taskWaitingVo.setProcessDefinitionVersion(pi.getProcessDefinitionVersion());
            taskWaitingVo.setProcessDefinitionName(pi.getProcessDefinitionName());
            taskWaitingVo.setBusinessKey(pi.getBusinessKey());
            list.add(taskWaitingVo);
        }
        return new TableDataInfo(list, total);
    }

    /**
     * ????????????
     * @param backProcessVo
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String backProcess(BackProcessVo backProcessVo) {

        Task task = taskService.createTaskQuery().taskId(backProcessVo.getTaskId()).taskAssignee(getUserId().toString()).singleResult();
        if (task.isSuspended()) {
            throw new ServiceException("????????????????????????");
        }
        if (ObjectUtil.isNull(task)) {
            throw new ServiceException("????????????????????????????????????????????????");
        }
        //????????????id
        String processInstanceId = task.getProcessInstanceId();
        // 1. ???????????????????????? BpmnModel
        BpmnModel bpmnModel = repositoryService.getBpmnModel(task.getProcessDefinitionId());
        // 2.??????????????????
        FlowNode curFlowNode = (FlowNode) bpmnModel.getFlowElement(task.getTaskDefinitionKey());
        // 3.?????????????????????????????????
        List<SequenceFlow> sequenceFlowList = curFlowNode.getOutgoingFlows();
        // 4. ??????????????????????????????????????????
        List<SequenceFlow> oriSequenceFlows = new ArrayList<>();
        oriSequenceFlows.addAll(sequenceFlowList);
        // 5. ?????????????????????????????????
        sequenceFlowList.clear();
        // 6. ????????????????????????
        FlowNode targetFlowNode = (FlowNode) bpmnModel.getFlowElement(backProcessVo.getTargetActivityId());
        // 7. ?????????????????????????????????
        List<SequenceFlow> incomingFlows = targetFlowNode.getIncomingFlows();
        // 8. ????????????????????????
        List<SequenceFlow> targetSequenceFlow = new ArrayList<>();
        for (SequenceFlow incomingFlow : incomingFlows) {
            // ???????????????????????????????????????????????????????????????
            FlowNode source = (FlowNode) incomingFlow.getSourceFlowElement();
            List<SequenceFlow> sequenceFlows;
            if (source instanceof ParallelGateway) {
                // ????????????: ??????????????????????????????????????????????????????????????????
                sequenceFlows = source.getOutgoingFlows();
            } else {
                // ?????????????????????, ????????????????????????????????????
                sequenceFlows = targetFlowNode.getIncomingFlows();
            }
            targetSequenceFlow.addAll(sequenceFlows);
        }
        // 9. ??????????????????????????????????????????
        curFlowNode.setOutgoingFlows(targetSequenceFlow);
        // 10. ????????????????????????????????????????????????????????????????????????
        List<Task> list = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
        for (Task t : list) {
            if (backProcessVo.getTaskId().equals(t.getId())) {
                // ?????????????????????????????????
                taskService.addComment(t.getId(), processInstanceId, StringUtils.isNotBlank(backProcessVo.getComment()) ? backProcessVo.getComment() : "??????");
                // ????????????????????????????????????????????????????????????????????????????????????
                taskService.complete(backProcessVo.getTaskId());
            } else {
                taskService.complete(t.getId());
                historyService.deleteHistoricTaskInstance(t.getId());
                iActHiActInstService.deleteActHiActInstByActId(t.getTaskDefinitionKey());
            }
        }
        // 11. ?????????????????????????????????????????????????????????????????????
        curFlowNode.setOutgoingFlows(oriSequenceFlows);
       // ??????????????????
        LambdaQueryWrapper<ActNodeAssignee> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ActNodeAssignee::getNodeId,backProcessVo.getTargetActivityId());
        wrapper.eq(ActNodeAssignee::getProcessDefinitionId,task.getProcessDefinitionId());
        ActNodeAssignee actNodeAssignee = iActNodeAssigneeService.getOne(wrapper);
        List<Task> newTaskList = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
        if(ObjectUtil.isNotEmpty(actNodeAssignee)&&!actNodeAssignee.getMultiple()){
            for (Task newTask : newTaskList) {
                // ???????????????????????????
                HistoricTaskInstance oldTargerTask = historyService.createHistoricTaskInstanceQuery()
                    .taskDefinitionKey(newTask.getTaskDefinitionKey()) // ??????id
                    .processInstanceId(processInstanceId)
                    .finished() // ????????????????????????
                    .orderByTaskCreateTime().desc() // ???????????????????????????
                    .list().get(0);
                taskService.setAssignee(newTask.getId(), oldTargerTask.getAssignee());
            }
        }

        // 13. ??????????????????????????????
        ActTaskNode actTaskNode = iActTaskNodeService.getListByInstanceIdAndNodeId(task.getProcessInstanceId(), backProcessVo.getTargetActivityId());
        if (ObjectUtil.isNotNull(actTaskNode) && actTaskNode.getOrderNo() == 0) {
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
            List<Task> newList = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
            for (Task ta : newList) {
                Map<String, Object> variables = new HashMap<>();
                variables.put("status", BusinessStatusEnum.BACK.getStatus());
                taskService.setVariables(ta.getId(), variables);
            }
            iActBusinessStatusService.updateState(processInstance.getBusinessKey(), BusinessStatusEnum.BACK);
        }
        iActTaskNodeService.deleteBackTaskNode(processInstanceId, backProcessVo.getTargetActivityId());
        //????????????????????????????????????
        if(ObjectUtil.isNotEmpty(actNodeAssignee)&&!actNodeAssignee.getMultiple()){
            List<ActRuExecution> actRuExecutions = iActRuExecutionService.selectRuExecutionByProcInstId(processInstanceId);
            for (ActRuExecution actRuExecution : actRuExecutions) {
                if(StringUtils.isNotBlank(actRuExecution.getActId())&&actRuExecution.getIsActive()==0){
                    iActRuExecutionService.deleteWithValidByIds(Arrays.asList(actRuExecution.getId()),false);
                }
            }
        }
        return processInstanceId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean backProcess2(BackProcessVo backProcessVo) {
        String taskId = backProcessVo.getTaskId();
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        String processInstanceId = task.getProcessInstanceId();
        JumpAnyWhereCmd jumpAnyWhereCmd = new JumpAnyWhereCmd
            (backProcessVo.getTaskId(),backProcessVo.getTargetActivityId(),repositoryService);
        managementService.executeCommand(jumpAnyWhereCmd);
        /*String processInstanceId = task.getProcessInstanceId();
        //????????????id
        String currentActivityId = task.getTaskDefinitionKey();
        //?????????????????????id
        String targetActivityId = backProcessVo.getTargetActivityId();
        //??????????????????
        String processDefinitionId = task.getProcessDefinitionId();
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);

        //??????????????????
        FlowElement currentFlowElement = bpmnModel.getFlowElement(currentActivityId);

        //??????????????????
        FlowElement targetFlowElement = bpmnModel.getFlowElement(targetActivityId);
        //????????????
        SequenceFlow newSequenceFlow  = new SequenceFlow();
        String id = IdUtil.getSnowflake().nextIdStr();
        newSequenceFlow .setId(id);
        newSequenceFlow.setSourceFlowElement(currentFlowElement);
        newSequenceFlow.setTargetFlowElement(targetFlowElement);
        //????????????
        newSequenceFlow.setConditionExpression("${\"+id+\"==\"" + id + "\"}");
        bpmnModel.getMainProcess().addFlowElement(newSequenceFlow);
        //??????
        taskService.addComment(task.getId(), task.getProcessInstanceId(), "????????????");
        //????????????
        taskService.complete(task.getId());
        //????????????
        bpmnModel.getMainProcess().removeFlowElement(id);

        List<Task> newTaskList = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
        for (Task newTask : newTaskList) {
            HistoricTaskInstance singleResult = historyService.createHistoricTaskInstanceQuery().taskId(backProcessVo.getTaskId()).singleResult();
            taskService.setAssignee(newTask.getId(), singleResult.getAssignee());
        }*/
        // 13. ??????????????????????????????
        ActTaskNode actTaskNode = iActTaskNodeService.getListByInstanceIdAndNodeId(task.getProcessInstanceId(), backProcessVo.getTargetActivityId());
        if(ObjectUtil.isNotNull(actTaskNode)&&actTaskNode.getOrderNo()==0){
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
            List<Task> newList = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
            for (Task t : newList) {
                Map<String, Object> variables =new HashMap<>();
                variables.put("status",BusinessStatusEnum.BACK.getStatus());
                taskService.setVariables(t.getId(),variables);
            }
            iActBusinessStatusService.updateState(processInstance.getBusinessKey(),BusinessStatusEnum.BACK);
        }
        Boolean taskNode = iActTaskNodeService.deleteBackTaskNode(processInstanceId, backProcessVo.getTargetActivityId());
        return taskNode;
    }

    /**
     * ?????????????????????????????????????????????
     * @param processInstId
     * @return
     */
    @Override
    public List<ActTaskNode> getBackNodes(String processInstId) {
        List<ActTaskNode> list = iActTaskNodeService.getListByInstanceId(processInstId);
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean delegateTask(TaskREQ taskREQ) {
        if(StringUtils.isBlank(taskREQ.getDelegateUserId())){
            throw new ServiceException("??????????????????");
        }
        TaskEntity task = (TaskEntity) taskService.createTaskQuery().taskId(taskREQ.getTaskId())
            .taskCandidateOrAssigned(LoginHelper.getUserId().toString()).singleResult();
        if(ObjectUtil.isEmpty(task)){
            throw new ServiceException("????????????????????????????????????????????????");
        }
        try{
            TaskEntity subTask = this.createSubTask(task,new Date());
            taskService.addComment(subTask.getId(), task.getProcessInstanceId(),"???"+LoginHelper.getUsername()+"???????????????"+taskREQ.getDelegateUserName()+"???");
            //????????????
            taskService.delegateTask(taskREQ.getTaskId(), taskREQ.getDelegateUserId());
            //???????????????????????????
            taskService.complete(subTask.getId());
            ActHiTaskInst actHiTaskInst = new ActHiTaskInst();
            actHiTaskInst.setId(task.getId());
            actHiTaskInst.setStartTime(new Date());
            iActHiTaskInstService.updateById(actHiTaskInst);
            return true;
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Boolean> transmit(String taskId, String userId) {
        Task task = taskService.createTaskQuery().taskId(taskId)
            .taskCandidateOrAssigned(LoginHelper.getUserId().toString()).singleResult();
        if(ObjectUtil.isEmpty(task)){
            return R.fail("????????????????????????????????????????????????");
        }
        try {
            TaskEntity subTask = createSubTask(task, new Date());
            taskService.addComment(subTask.getId(), task.getProcessInstanceId(),
                "???"+LoginHelper.getUsername()+"??????????????????"+iSysUserService.selectUserById(Long.valueOf(userId)).getUserName()+"???");
            taskService.complete(subTask.getId());
            taskService.setAssignee(task.getId(),userId);
            return R.ok();
        }catch (Exception e){
            e.printStackTrace();
            return R.fail();
        }
    }

    /**
     * ??????????????????
     * @param parentTask
     * @param createTime
     * @return
     */
    private TaskEntity createSubTask(Task parentTask,Date createTime){
        TaskEntity task = null;
        if(ObjectUtil.isNotEmpty(parentTask)){
            task = (TaskEntity) taskService.newTask();
            task.setCategory(parentTask.getCategory());
            task.setDescription(parentTask.getDescription());
            task.setTenantId(parentTask.getTenantId());
            task.setAssignee(parentTask.getAssignee());
            task.setName(parentTask.getName());
            task.setProcessDefinitionId(parentTask.getProcessDefinitionId());
            task.setProcessInstanceId(parentTask.getProcessInstanceId());
            task.setTaskDefinitionKey(parentTask.getTaskDefinitionKey());
            task.setPriority(parentTask.getPriority());
            task.setCreateTime(createTime);
            taskService.saveTask(task);
        }
        if(ObjectUtil.isNotNull(task)){
            ActHiTaskInst hiTaskInst = iActHiTaskInstService.getById(task.getId());
            if(ObjectUtil.isNotEmpty(hiTaskInst)){
                hiTaskInst.setProcDefId(task.getProcessDefinitionId());
                hiTaskInst.setProcInstId(task.getProcessInstanceId());
                hiTaskInst.setTaskDefKey(task.getTaskDefinitionKey());
                iActHiTaskInstService.updateById(hiTaskInst);
            }
        }
        return  task;
    }

}
