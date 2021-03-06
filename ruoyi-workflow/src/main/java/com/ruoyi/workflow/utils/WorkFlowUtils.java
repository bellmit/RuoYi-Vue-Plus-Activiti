package com.ruoyi.workflow.utils;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysUserRole;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.mapper.SysUserRoleMapper;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.workflow.common.constant.ActConstant;
import com.ruoyi.workflow.common.enums.BusinessStatusEnum;
import com.ruoyi.workflow.domain.ActBusinessStatus;
import com.ruoyi.workflow.domain.ActFullClassParam;
import com.ruoyi.workflow.domain.vo.ActFullClassVo;
import com.ruoyi.workflow.domain.vo.ProcessNode;
import com.ruoyi.workflow.service.IActBusinessStatusService;
import de.odysseus.el.ExpressionFactoryImpl;
import de.odysseus.el.util.SimpleContext;
import de.odysseus.el.util.SimpleResolver;
import lombok.SneakyThrows;
import org.activiti.bpmn.converter.BpmnXMLConverter;
import org.activiti.bpmn.model.*;
import org.activiti.editor.language.json.converter.BpmnJsonConverter;
import org.activiti.engine.ProcessEngine;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.delegate.Expression;
import org.activiti.engine.impl.Condition;
import org.activiti.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.activiti.engine.impl.context.Context;
import org.activiti.engine.impl.el.UelExpressionCondition;
import org.activiti.engine.impl.persistence.entity.ExecutionEntityImpl;
import org.activiti.engine.impl.persistence.entity.VariableInstance;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.el.ExpressionFactory;
import javax.el.ValueExpression;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.ruoyi.workflow.common.constant.ActConstant.*;

/**
 * @program: ruoyi-vue-plus
 * @description: ??????????????????
 * @author: gssong
 * @created: 2021/10/03 19:31
 */
@Component
public class WorkFlowUtils {
    @Resource
    public ObjectMapper objectMapper;

    @Autowired
    private IActBusinessStatusService iActBusinessStatusService;

    @Autowired
    private TaskService taskService;

    @Resource
    private ProcessEngine processEngine;

    @Autowired
    private RuntimeService runtimeService;


    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;


    /**
     * @Description: bpmnModel??????xml
     * @param: jsonBytes
     * @return: byte[]
     * @Author: gssong
     * @Date: 2021/11/5
     */
    public byte[] bpmnJsonXmlBytes(byte[] jsonBytes) throws IOException {
        if (jsonBytes == null) {
            return null;
        }
        // 1. json??????????????? BpmnModel ??????
        JsonNode jsonNode = objectMapper.readTree(jsonBytes);
        BpmnModel bpmnModel = new BpmnJsonConverter().convertToBpmnModel(jsonNode);

        if (bpmnModel.getProcesses().size() == 0) {
            return null;
        }
        //2.???bpmnModel??????xml
        byte[] bytes = new BpmnXMLConverter().convertToXML(bpmnModel);
        return bytes;
    }

    /**
     * ??????????????????????????????
     *
     * @param flowElement
     * @param nextNodes
     * @param tempNodes
     * @param taskId
     * @param businessKey
     * @param gateway
     */
    public void getNextNodes(FlowElement flowElement, ExecutionEntityImpl executionEntity,List<ProcessNode> nextNodes, List<ProcessNode> tempNodes, String taskId, String businessKey, String gateway) {
        // ?????????????????????????????????
        List<SequenceFlow> outgoingFlows = ((FlowNode) flowElement).getOutgoingFlows();
        // ???????????????????????????????????????
        for (SequenceFlow sequenceFlow : outgoingFlows) {
            // ???????????????????????????
            ProcessNode processNode = new ProcessNode();
            ProcessNode tempNode = new ProcessNode();
            FlowElement outFlowElement = sequenceFlow.getTargetFlowElement();
            if (outFlowElement instanceof UserTask) {
                // ?????????????????????????????????????????????????????????????????????
                // ???????????????????????????
                if (ActConstant.EXCLUSIVEGATEWAY.equals(gateway)) {
                    String conditionExpression = sequenceFlow.getConditionExpression();
                    //?????????????????????
                    if (StringUtils.isNotBlank(conditionExpression)) {
                        ProcessEngineConfigurationImpl processEngineConfiguration = (ProcessEngineConfigurationImpl)processEngine.getProcessEngineConfiguration();
                        Context.setCommandContext(processEngineConfiguration.getCommandContextFactory().createCommandContext(null));
                        Context.setProcessEngineConfiguration(processEngineConfiguration);
                        Expression expression = Context.getProcessEngineConfiguration().getExpressionManager().createExpression(conditionExpression);
                        Condition condition = new UelExpressionCondition(expression);
                        boolean evaluate = condition.evaluate(sequenceFlow.getId(), executionEntity);
                        if (evaluate) {
                            processNode.setNodeId(outFlowElement.getId());
                            processNode.setNodeName(outFlowElement.getName());
                            processNode.setNodeType(ActConstant.EXCLUSIVEGATEWAY);
                            processNode.setTaskId(taskId);
                            processNode.setExpression(true);
                            processNode.setChooseWay(ActConstant.WORKFLOW_ASSIGNEE);
                            processNode.setAssignee(((UserTask) outFlowElement).getAssignee());
                            processNode.setAssigneeId(((UserTask) outFlowElement).getAssignee());
                            nextNodes.add(processNode);
                        } else {
                            processNode.setNodeId(outFlowElement.getId());
                            processNode.setNodeName(outFlowElement.getName());
                            processNode.setNodeType(ActConstant.EXCLUSIVEGATEWAY);
                            processNode.setTaskId(taskId);
                            processNode.setExpression(false);
                            processNode.setChooseWay(ActConstant.WORKFLOW_ASSIGNEE);
                            processNode.setAssignee(((UserTask) outFlowElement).getAssignee());
                            processNode.setAssigneeId(((UserTask) outFlowElement).getAssignee());
                            nextNodes.add(processNode);
                        }
                    } else {
                        tempNode.setNodeId(outFlowElement.getId());
                        tempNode.setNodeName(outFlowElement.getName());
                        tempNode.setNodeType(ActConstant.EXCLUSIVEGATEWAY);
                        tempNode.setTaskId(taskId);
                        tempNode.setExpression(true);
                        tempNode.setChooseWay(ActConstant.WORKFLOW_ASSIGNEE);
                        tempNode.setAssignee(((UserTask) outFlowElement).getAssignee());
                        tempNode.setAssigneeId(((UserTask) outFlowElement).getAssignee());
                        tempNodes.add(tempNode);
                    }

                } else {
                    processNode.setNodeId(outFlowElement.getId());
                    processNode.setNodeName(outFlowElement.getName());
                    processNode.setNodeType(ActConstant.USER_TASK);
                    processNode.setTaskId(taskId);
                    processNode.setExpression(false);
                    processNode.setChooseWay(ActConstant.WORKFLOW_ASSIGNEE);
                    processNode.setAssignee(((UserTask) outFlowElement).getAssignee());
                    processNode.setAssigneeId(((UserTask) outFlowElement).getAssignee());
                    nextNodes.add(processNode);
                }
            } else if (outFlowElement instanceof EndEvent) {
                break;
            } else if (outFlowElement instanceof ExclusiveGateway) { // ????????????
                getNextNodes(outFlowElement, executionEntity, nextNodes, tempNodes, taskId, businessKey, ActConstant.EXCLUSIVEGATEWAY);
            } else if (outFlowElement instanceof ParallelGateway) { //????????????
                getNextNodes(outFlowElement,executionEntity, nextNodes, tempNodes, taskId, businessKey, ActConstant.PARALLELGATEWAY);
            } else {
                throw new ServiceException("????????????????????????");
            }
        }
    }

    /**
     * @Description: ??????uel?????????
     * @param: uel uel??????
     * @param: businessKey ??????key
     * @param: taskId ??????id
     * @return: boolean
     * @Author: gssong
     * @Date: 2021/11/5
     */
    public boolean isCondition(String uel, String businessKey, String taskId) {
        Map<String, VariableInstance> variables = taskService.getVariableInstances(taskId);

        Class className = null;
        Object entity = null;
        ActBusinessStatus actBusinessStatus = iActBusinessStatusService.getInfoByBusinessKey(businessKey);
        if (ObjectUtil.isNull(actBusinessStatus)) {
            throw new ServiceException("?????????????????????");
        }

        for (Map.Entry<String, VariableInstance> variable : variables.entrySet()) {
            // ????????? key
            String name = variable.getValue().getName();
            // ????????? value
            String textValue = variable.getValue().getTextValue();
            // ????????????
            String typeName = variable.getValue().getTypeName();
            if (ActConstant.JSON.equals(typeName) && uel.contains(name)) {
                try {
                    //?????????????????????
                    className = Class.forName(actBusinessStatus.getClassFullName());
                    //????????????
                    entity = objectMapper.readValue(textValue, className);
                    ExpressionFactory factory = new ExpressionFactoryImpl();
                    SimpleContext context = new SimpleContext(new SimpleResolver());
                    context.setVariable(name, factory.createValueExpression(entity, className));
                    ValueExpression exp = factory.createValueExpression(context, uel, boolean.class);
                    if ((boolean) exp.getValue(context)) {
                        return true;
                    }
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                    throw new ServiceException("?????????????????????");
                } catch (JsonProcessingException ex) {
                    ex.printStackTrace();
                    throw new ServiceException("JSON????????????");
                }
            } else if (uel.contains(name)) {
                ExpressionFactory factory = new ExpressionFactoryImpl();
                SimpleContext context = new SimpleContext(new SimpleResolver());
                if (ActConstant.STRING.equals(typeName)) {
                    context.setVariable(name, factory.createValueExpression(textValue, String.class));
                } else if (ActConstant.INTEGER.equals(typeName)) {
                    context.setVariable(name, factory.createValueExpression(textValue, Integer.class));
                } else if (ActConstant.LONG.equals(typeName)) {
                    context.setVariable(name, factory.createValueExpression(textValue, Long.class));
                } else if (ActConstant.DOUBLE.equals(typeName)) {
                    context.setVariable(name, factory.createValueExpression(textValue, Double.class));
                } else if (ActConstant.SHORT.equals(typeName)) {
                    context.setVariable(name, factory.createValueExpression(textValue, Short.class));
                } else if (ActConstant.DATE.equals(typeName)) {
                    context.setVariable(name, factory.createValueExpression(textValue, Date.class));
                }
                ValueExpression exp = factory.createValueExpression(context, uel, boolean.class);
                if ((boolean) exp.getValue(context)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     *
     * @param actFullClass ??????????????????
     * @param taskId ??????id
     * @return ??????????????????
     */
    @SneakyThrows
    public Object assignList(ActFullClassVo actFullClass, String taskId) {
        //????????????
        String methodName = actFullClass.getMethod();
        //?????????
        String fullClass = actFullClass.getFullClass();
        List<Object> params = new ArrayList<>();

        List<ActFullClassParam> fullClassParam = actFullClass.getFullClassParam();
        for (ActFullClassParam param : fullClassParam) {
            Map<String, VariableInstance> variables = taskService.getVariableInstances(taskId);
            if (variables.containsKey(param.getParam())) {
                VariableInstance v = variables.get(param.getParam());
                String variable = v.getTextValue();
                if (param.getParamType().equals(ActConstant.PARAM_BYTE)) {
                    params.add(String.valueOf(variable));
                } else if (param.getParamType().equals(ActConstant.PARAM_SHORT)) {
                    params.add(Short.valueOf(variable));
                } else if (param.getParamType().equals(ActConstant.PARAM_INTEGER)) {
                    params.add(Integer.valueOf(variable));
                } else if (param.getParamType().equals(ActConstant.PARAM_LONG)) {
                    params.add(Long.valueOf(variable));
                } else if (param.getParamType().equals(ActConstant.PARAM_FLOAT)) {
                    params.add(Float.valueOf(variable));
                } else if (param.getParamType().equals(ActConstant.PARAM_DOUBLE)) {
                    params.add(Double.valueOf(variable));
                } else if (param.getParamType().equals(ActConstant.PARAM_BOOLEAN)) {
                    params.add(Boolean.valueOf(variable));
                } else if (param.getParamType().equals(ActConstant.PARAM_CHARACTER)) {
                    params.add(variable.charAt(variable.length()));
                }
            }
        }
        Class<?> aClass = Class.forName(fullClass);
        Method[] declaredMethods = aClass.getDeclaredMethods();
        for (Method declaredMethod : declaredMethods) {
            if (methodName.equals(declaredMethod.getName())) {
                Class<?>[] parameterTypes = declaredMethod.getParameterTypes();
                Method method = aClass.getDeclaredMethod(declaredMethod.getName(), parameterTypes);
                Object newInstance = method.getDeclaringClass().newInstance();
                Object invoke = method.invoke(newInstance, params.toArray());
                return invoke;
            }
        }
        throw new ServiceException("?????????????????????");
    }

    /**
     * @Description: ????????????????????????
     * @param o ??????
     * @param idList ????????????
     * @param id ??????id
     * @Author: gssong
     * @Date: 2022/1/16
     */
    public void setStatusFileValue(Object o, List<String> idList, String id){
        Class<?> aClass = o.getClass();
        Field businessStatus = null;
        try {
            businessStatus = aClass.getDeclaredField(ACT_BUSINESSS_TATUS);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
            throw new ServiceException("?????????"+ACT_BUSINESSS_TATUS+"??????");
        }
        businessStatus.setAccessible(true);
        List<ActBusinessStatus> infoByBusinessKey = iActBusinessStatusService.getListInfoByBusinessKey(idList);
        try {
            if(CollectionUtil.isNotEmpty(infoByBusinessKey)){
                ActBusinessStatus actBusinessStatus = infoByBusinessKey.stream().filter(e -> e.getBusinessKey().equals(id)).findFirst().orElse(null);
                if(ObjectUtil.isNotEmpty(actBusinessStatus)){
                    businessStatus.set(o,actBusinessStatus);
                }else{
                    businessStatus.set(o,new ActBusinessStatus().setStatus(BusinessStatusEnum.DRAFT.getStatus()));
                }
            }else{
                businessStatus.set(o,new ActBusinessStatus().setStatus(BusinessStatusEnum.DRAFT.getStatus()));
            }
        }catch (Exception e){
            e.printStackTrace();
            throw new ServiceException("????????????????????????");
        }
    }

    /**
     * @Description: ??????????????????id
     * @param o ??????
     * @param idList ????????????
     * @param id ??????id
     * @Author: gssong
     * @Date: 2022/1/16
     */
    public void setProcessInstIdFileValue(Object o, List<String> idList, String id){
        Class<?> aClass = o.getClass();
        Field processInstanceId = null;
        try {
            processInstanceId = aClass.getDeclaredField(PROCESS_INSTANCE_ID);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
            throw new ServiceException("?????????"+PROCESS_INSTANCE_ID+"??????");
        }
        processInstanceId.setAccessible(true);
        List<ActBusinessStatus> infoByBusinessKey = iActBusinessStatusService.getListInfoByBusinessKey(idList);
        try {
            if(CollectionUtil.isNotEmpty(infoByBusinessKey)){
                ActBusinessStatus actBusinessStatus = infoByBusinessKey.stream().filter(e -> e.getBusinessKey().equals(id)).findFirst().orElse(null);
                if(ObjectUtil.isNotEmpty(actBusinessStatus)){
                    processInstanceId.set(o,actBusinessStatus.getProcessInstanceId());
                }else{
                    processInstanceId.set(o,"");
                }
            }else{
                processInstanceId.set(o,"");
            }
        }catch (Exception e){
            e.printStackTrace();
            throw new ServiceException("????????????????????????");
        }
    }

    /**
     * ???????????????
     *
     * @param params
     * @param chooseWay
     * @param nodeName
     * @return
     */
    public List<Long> assignees(String params, String chooseWay, String nodeName) {
        List<Long> paramList = new ArrayList<>();
        String[] split = params.split(",");
        for (String userId : split) {
            paramList.add(Long.valueOf(userId));
        }
        List<SysUser> list = null;
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        // ?????????id??????
        if (WORKFLOW_PERSON.equals(chooseWay)) {
            queryWrapper.in(SysUser::getUserId, paramList);
            list = sysUserMapper.selectList(queryWrapper);
            //?????????id????????????
        }else if (WORKFLOW_ROLE.equals(chooseWay)) {
            List<SysRole> sysRoles = sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>().in(SysRole::getRoleId, paramList));
            if (CollectionUtil.isNotEmpty(sysRoles)) {
                List<Long> collectRoleId = sysRoles.stream().map(e -> e.getRoleId()).collect(Collectors.toList());
                List<SysUserRole> sysUserRoles = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getRoleId, collectRoleId));
                queryWrapper.in(SysUser::getUserId, sysUserRoles.stream().map(e -> e.getUserId()).collect(Collectors.toList()));
                list = sysUserMapper.selectList(queryWrapper);
            }
            //?????????id????????????
        } else if (WORKFLOW_DEPT.equals(chooseWay)) {
            queryWrapper.in(SysUser::getDeptId, paramList);
            list = sysUserMapper.selectList(queryWrapper);
        }
        if (CollectionUtil.isEmpty(list)) {
            throw new ServiceException(nodeName + "????????????????????????");
        }
        return list.stream().map(e -> e.getUserId()).collect(Collectors.toList());
    }
}
