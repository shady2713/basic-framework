package com.basicframework.module.system.service.sms;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.date.DateUtils.isToday;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.map.MapUtil;
import com.basicframework.module.system.api.sms.dto.code.SmsCodeSendReqDTO;
import com.basicframework.module.system.api.sms.dto.code.SmsCodeUseReqDTO;
import com.basicframework.module.system.api.sms.dto.code.SmsCodeValidateReqDTO;
import com.basicframework.module.system.dal.dataobject.sms.SmsCodeDO;
import com.basicframework.module.system.dal.mysql.sms.SmsCodeMapper;
import com.basicframework.module.system.enums.sms.SmsSceneEnum;
import com.basicframework.module.system.framework.sms.config.SmsCodeProperties;
import jakarta.annotation.Resource;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 短信验证码 Service 实现类
 *
 */
@Service
@Validated
public class SmsCodeServiceImpl implements SmsCodeService {

    private static final int SMS_CODE_LENGTH = 6;
    private static final int SMS_CODE_BOUND = 1_000_000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Resource
    private SmsCodeProperties smsCodeProperties;

    @Resource
    private SmsCodeMapper smsCodeMapper;

    @Resource
    private SmsSendService smsSendService;

    @Override
    public void sendSmsCode(SmsCodeSendReqDTO reqDTO) {
        SmsSceneEnum sceneEnum = SmsSceneEnum.getCodeByScene(reqDTO.getScene());
        if (sceneEnum == null) {
            throw exception(SMS_CODE_SCENE_NOT_EXISTS, reqDTO.getScene());
        }
        // 创建验证码
        String code = createSmsCode(reqDTO.getMobile(), reqDTO.getScene(), reqDTO.getCreateIp());
        // 发送验证码
        smsSendService.sendSingleSms(
                reqDTO.getMobile(), null, null, sceneEnum.getTemplateCode(), MapUtil.of("code", code));
    }

    private String createSmsCode(String mobile, Integer scene, String ip) {
        // 校验是否可以发送验证码，不用筛选场景
        SmsCodeDO lastSmsCode = smsCodeMapper.selectLastByMobile(mobile, null, null);
        if (lastSmsCode != null) {
            if (LocalDateTimeUtil.between(lastSmsCode.getCreateTime(), LocalDateTime.now())
                            .toMillis()
                    < smsCodeProperties.getSendFrequency().toMillis()) { // 发送过于频繁
                throw exception(SMS_CODE_SEND_TOO_FAST);
            }
            if (isToday(lastSmsCode.getCreateTime())
                    && // 必须是今天，才能计算超过当天的上限
                    lastSmsCode.getTodayIndex() >= smsCodeProperties.getSendMaximumQuantityPerDay()) { // 超过当天发送的上限。
                throw exception(SMS_CODE_EXCEED_SEND_MAXIMUM_QUANTITY_PER_DAY);
            }
            // 当前仅按手机号做发送频控，IP 维度限流后续如有明确需求再单独补充。
        }

        // 创建验证码记录
        String code = String.format("%0" + SMS_CODE_LENGTH + "d", SECURE_RANDOM.nextInt(SMS_CODE_BOUND));
        SmsCodeDO newSmsCode = SmsCodeDO.builder()
                .mobile(mobile)
                .code(code)
                .scene(scene)
                .todayIndex(
                        lastSmsCode != null && isToday(lastSmsCode.getCreateTime())
                                ? lastSmsCode.getTodayIndex() + 1
                                : 1)
                .createIp(ip)
                .used(false)
                .build();
        smsCodeMapper.insert(newSmsCode);
        return code;
    }

    @Override
    public void useSmsCode(SmsCodeUseReqDTO reqDTO) {
        // 检测验证码是否有效
        SmsCodeDO lastSmsCode = validateSmsCode0(reqDTO.getMobile(), reqDTO.getCode(), reqDTO.getScene());
        // 使用验证码
        smsCodeMapper.updateById(SmsCodeDO.builder()
                .id(lastSmsCode.getId())
                .used(true)
                .usedTime(LocalDateTime.now())
                .usedIp(reqDTO.getUsedIp())
                .build());
    }

    @Override
    public void validateSmsCode(SmsCodeValidateReqDTO reqDTO) {
        validateSmsCode0(reqDTO.getMobile(), reqDTO.getCode(), reqDTO.getScene());
    }

    private SmsCodeDO validateSmsCode0(String mobile, String code, Integer scene) {
        // 校验验证码
        SmsCodeDO lastSmsCode = smsCodeMapper.selectLastByMobile(mobile, code, scene);
        // 若验证码不存在，抛出异常
        if (lastSmsCode == null) {
            throw exception(SMS_CODE_NOT_EXISTS);
        }
        // 超过时间
        if (LocalDateTimeUtil.between(lastSmsCode.getCreateTime(), LocalDateTime.now())
                        .toMillis()
                >= smsCodeProperties.getExpireTimes().toMillis()) { // 验证码已过期
            throw exception(SMS_CODE_EXPIRED);
        }
        // 判断验证码是否已被使用
        if (Boolean.TRUE.equals(lastSmsCode.getUsed())) {
            throw exception(SMS_CODE_USED);
        }
        return lastSmsCode;
    }
}
