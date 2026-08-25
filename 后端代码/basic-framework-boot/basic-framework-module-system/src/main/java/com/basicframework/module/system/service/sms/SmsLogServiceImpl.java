package com.basicframework.module.system.service.sms;

import com.basicframework.module.system.dal.dataobject.sms.SmsLogDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import com.basicframework.module.system.dal.mysql.sms.SmsLogMapper;
import com.basicframework.module.system.enums.sms.SmsReceiveStatusEnum;
import com.basicframework.module.system.enums.sms.SmsSendStatusEnum;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 短信日志 Service 实现类（仅保留写入方法）
 */
@Service
@Slf4j
public class SmsLogServiceImpl implements SmsLogService {

    @Resource
    private SmsLogMapper smsLogMapper;

    @Override
    public Long createSmsLog(
            String mobile,
            Long userId,
            Integer userType,
            Boolean isSend,
            SmsTemplateDO template,
            String templateContent,
            Map<String, Object> templateParams) {
        SmsLogDO smsLogDO = SmsLogDO.builder()
                .mobile(mobile)
                .userId(userId)
                .userType(userType)
                .channelId(template.getChannelId())
                .channelCode(template.getChannelCode())
                .templateId(template.getId())
                .templateCode(template.getCode())
                .templateType(template.getType())
                .templateContent(templateContent)
                .templateParams(templateParams)
                .apiTemplateId(template.getApiTemplateId())
                .sendStatus(isSend ? SmsSendStatusEnum.INIT.getStatus() : SmsSendStatusEnum.IGNORE.getStatus())
                .receiveStatus(SmsReceiveStatusEnum.INIT.getStatus())
                .build();
        smsLogMapper.insert(smsLogDO);
        return smsLogDO.getId();
    }

    @Override
    public void updateSmsSendResult(
            Long id, Boolean success, String apiSendCode, String apiSendMsg, String apiRequestId, String apiSerialNo) {
        SmsLogDO updateObj = new SmsLogDO();
        updateObj.setId(id);
        updateObj.setSendStatus(
                success ? SmsSendStatusEnum.SUCCESS.getStatus() : SmsSendStatusEnum.FAILURE.getStatus());
        updateObj.setSendTime(LocalDateTime.now());
        updateObj.setApiSendCode(apiSendCode);
        updateObj.setApiSendMsg(apiSendMsg);
        updateObj.setApiRequestId(apiRequestId);
        updateObj.setApiSerialNo(apiSerialNo);
        smsLogMapper.updateById(updateObj);
    }

    @Override
    public void updateSmsReceiveResult(
            Long id,
            String apiSerialNo,
            Boolean success,
            LocalDateTime receiveTime,
            String apiErrorCode,
            String apiErrorMsg) {
        SmsLogDO updateObj = new SmsLogDO();
        updateObj.setId(id);
        updateObj.setReceiveStatus(
                success ? SmsReceiveStatusEnum.SUCCESS.getStatus() : SmsReceiveStatusEnum.FAILURE.getStatus());
        updateObj.setReceiveTime(receiveTime);
        updateObj.setApiSerialNo(apiSerialNo);
        updateObj.setApiReceiveCode(apiErrorCode);
        updateObj.setApiReceiveMsg(apiErrorMsg);
        smsLogMapper.updateById(updateObj);
    }
}
