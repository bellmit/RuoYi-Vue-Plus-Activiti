package com.ruoyi.workflow.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.workflow.common.constant.ActConstant;
import com.ruoyi.workflow.factory.WorkflowService;
import com.ruoyi.workflow.domain.bo.ModelAdd;
import com.ruoyi.workflow.domain.bo.ModelREQ;
import com.ruoyi.workflow.service.IModelService;
import com.ruoyi.workflow.utils.WorkFlowUtils;
import lombok.extern.slf4j.Slf4j;
import org.activiti.bpmn.converter.BpmnXMLConverter;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.editor.constants.ModelDataJsonConstants;
import org.activiti.editor.language.json.converter.BpmnJsonConverter;
import org.activiti.engine.repository.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
public class ModelServiceImpl extends WorkflowService implements IModelService {

    @Autowired
    private WorkFlowUtils workFlowUtils;

    @Override
    public TableDataInfo<Model> getByPage(ModelREQ modelReq) {
        ModelQuery query = repositoryService.createModelQuery();
        if (StringUtils.isNotEmpty(modelReq.getName())) {
            query.modelNameLike("%" + modelReq.getName() + "%");
        }
        if (StringUtils.isNotEmpty(modelReq.getKey())) {
            query.modelKey(modelReq.getKey());
        }
        //????????????????????????
        query.orderByCreateTime().desc();
        // ????????????
        List<Model> modelList = query.listPage(modelReq.getFirstResult(), modelReq.getPageSize());
        if (CollectionUtil.isNotEmpty(modelList)) {
            modelList.forEach(e -> {
                boolean isNull = JSONUtil.isNull(JSONUtil.parseObj(e.getMetaInfo()).get(ModelDataJsonConstants.MODEL_DESCRIPTION));
                if (!isNull) {
                    e.setMetaInfo((String) JSONUtil.parseObj(e.getMetaInfo()).get(ModelDataJsonConstants.MODEL_DESCRIPTION));
                } else {
                    e.setMetaInfo("");
                }
            });
        }
        // ????????????
        long total = query.count();
        return new TableDataInfo(modelList, total);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Model> add(ModelAdd modelAdd) throws UnsupportedEncodingException {
        int version = 0;

        Model checkModel = repositoryService.createModelQuery().modelKey(modelAdd.getKey()).singleResult();
        if(ObjectUtil.isNotNull(checkModel)){
            return R.fail("??????KEY?????????",null);
        }
        // 1. ??????????????????
        Model model = repositoryService.newModel();
        model.setName(modelAdd.getName());
        model.setKey(modelAdd.getKey());
        model.setVersion(version);

        // ????????????json??????
        ObjectNode objectNode = objectMapper.createObjectNode();
        objectNode.put(ModelDataJsonConstants.MODEL_NAME, modelAdd.getName());
        objectNode.put(ModelDataJsonConstants.MODEL_REVISION, version);
        objectNode.put(ModelDataJsonConstants.MODEL_DESCRIPTION, modelAdd.getDescription());
        model.setMetaInfo(objectNode.toString());
        // ??????????????????????????????????????????
        repositoryService.saveModel(model);

        // ??????????????????????????????json???
        // {"id":"canvas","resourceId":"canvas","stencilset":{"namespace":"http://b3mn.org/stencilset/bpmn2.0#"},"properties":{"process_id":"?????????"}}
        ObjectNode editorNode = objectMapper.createObjectNode();
        ObjectNode stencilSetNode = objectMapper.createObjectNode();
        stencilSetNode.put("namespace", ActConstant.NAMESPACE);
        editorNode.replace("stencilset", stencilSetNode);
        // ??????key
        ObjectNode propertiesNode = objectMapper.createObjectNode();
        propertiesNode.put("process_id", modelAdd.getKey());
        propertiesNode.put("name", modelAdd.getName());
        editorNode.replace("properties", propertiesNode);

        repositoryService.addModelEditorSource(model.getId(), editorNode.toString().getBytes(ActConstant.UTF_8));
        return R.ok(model);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deploy(String id) throws IOException {
        //1.????????????????????????json?????????
        byte[] jsonBytes = repositoryService.getModelEditorSource(id);
        if (jsonBytes == null) {
            return R.fail("?????????????????????????????????????????????????????????????????????");
        }
        // ???json??????????????? xml ??????????????????bpmn2.0???????????????????????????????????????xml???????????????activiti?????????????????????
        byte[] xmlBytes = workFlowUtils.bpmnJsonXmlBytes(jsonBytes);
        if (xmlBytes == null) {
            return R.fail("???????????????????????????????????????????????????????????????");
        }
        // 2. ?????????????????????????????????
        byte[] pngBytes = repositoryService.getModelEditorSourceExtra(id);

        // ???????????????????????????
        Model model = repositoryService.getModel(id);

        // xml??????????????? ?????????act_ge_bytearray?????????name_??????
        String processName = model.getName() + ".bpmn20.xml";
        // ???????????????????????????act_ge_bytearray?????????name_??????
        String pngName = model.getName() + "." + model.getKey() + ".png";

        // 3. ?????????????????????api??????????????????????????????
        Deployment deployment = repositoryService.createDeployment()
            .name(model.getName()) // ????????????
            .key(model.getKey()) // ????????????key
            .addString(processName, new String(xmlBytes, Constants.UTF8)) // bpmn20.xml??????
            .addBytes(pngName, pngBytes) // png??????
            .deploy();
        ProcessDefinitionQuery definitionQuery = repositoryService.createProcessDefinitionQuery();
        ProcessDefinition processDefinition = definitionQuery.deploymentId(deployment.getId()).singleResult();
        System.out.println(processDefinition.getName());
        // ?????? ??????id ?????????????????????????????????
        model.setDeploymentId(deployment.getId());
        repositoryService.saveModel(model);
        return R.ok();
    }

    @Override
    public void exportZip(String modelId, HttpServletResponse response) {
        ZipOutputStream zipos = null;
        try {
            zipos = new ZipOutputStream(response.getOutputStream());
            // ??????????????????
            String zipName = "???????????????";
            //1.????????????????????????
            Model model = repositoryService.getModel(modelId);
            if (ObjectUtil.isNotNull(model)) {
                // 2. ???????????????????????????json?????????
                byte[] bpmnJsonBytes = repositoryService.getModelEditorSource(modelId);
                // 2.1 ???json??????????????????xml?????????
                byte[] xmlBytes = workFlowUtils.bpmnJsonXmlBytes(bpmnJsonBytes);
                if (xmlBytes == null) {
                    zipName = "??????????????????-??????????????????????????????????????????";
                } else {
                    // ??????????????????
                    zipName = model.getName() + "." + model.getKey() + ".zip";
                    // ???xml?????????????????????(??????xml????????????????????????.bpmn20.xml
                    zipos.putNextEntry(new ZipEntry(model.getName() + ".bpmn20.xml"));
                    zipos.write(xmlBytes);
                    //3.???????????????????????????????????????
                    byte[] pngBytes = repositoryService.getModelEditorSourceExtra(modelId);
                    if (pngBytes != null) {
                        zipos.putNextEntry(new ZipEntry(model.getName() + "." + model.getKey() + ".png"));
                        zipos.write(pngBytes);
                    }
                }
            }
            response.setHeader("Content-Disposition",
                "attachment; filename=" + URLEncoder.encode(zipName, ActConstant.UTF_8) + ".zip");
            // ???????????????
            response.flushBuffer();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (zipos != null) {
                try {
                    zipos.closeEntry();
                    zipos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public Boolean convertToModel(String processDefinitionId) {
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
            .processDefinitionId(processDefinitionId).singleResult();
        InputStream bpmnStream = repositoryService.getResourceAsStream(pd.getDeploymentId(), pd.getResourceName());
        Model model = repositoryService.createModelQuery().modelKey(pd.getKey()).singleResult();
            try {
                XMLInputFactory xif = XMLInputFactory.newInstance();
                InputStreamReader in = new InputStreamReader(bpmnStream, ActConstant.UTF_8);
                XMLStreamReader xtr = xif.createXMLStreamReader(in);
                BpmnModel bpmnModel = new BpmnXMLConverter().convertToBpmnModel(xtr);
                BpmnJsonConverter converter = new BpmnJsonConverter();
                ObjectNode modelNode = converter.convertToJson(bpmnModel);
                if(ObjectUtil.isNotNull(model)){
                    repositoryService.addModelEditorSource(model.getId(), modelNode.toString().getBytes(ActConstant.UTF_8));
                    return true;
                }else{
                    Model modelData = repositoryService.newModel();
                    modelData.setKey(pd.getKey());
                    modelData.setName(pd.getName());

                    ObjectNode modelObjectNode = new ObjectMapper().createObjectNode();
                    modelObjectNode.put(ModelDataJsonConstants.MODEL_NAME, pd.getName());
                    modelObjectNode.put(ModelDataJsonConstants.MODEL_REVISION, modelData.getVersion());
                    modelObjectNode.put(ModelDataJsonConstants.MODEL_DESCRIPTION, pd.getDescription());
                    modelData.setMetaInfo(modelObjectNode.toString());
                    repositoryService.saveModel(modelData);
                    repositoryService.addModelEditorSource(modelData.getId(), modelNode.toString().getBytes(ActConstant.UTF_8));
                    return true;
                }

            } catch (Exception e) {
                e.printStackTrace();
                log.error("???????????????????????????:", e.getMessage());
                return false;
            }
    }
}
