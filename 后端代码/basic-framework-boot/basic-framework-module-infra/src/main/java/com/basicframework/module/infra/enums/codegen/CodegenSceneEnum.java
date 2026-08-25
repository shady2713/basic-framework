package com.basicframework.module.infra.enums.codegen;

import static cn.hutool.core.util.ArrayUtil.*;

import cn.hutool.core.util.ObjectUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 代码生成的场景枚举
 *
 */
@AllArgsConstructor
@Getter
public enum CodegenSceneEnum {
    ADMIN(1, "管理后台", "admin", ""),
    APP(2, "用户 APP", "app", "App");

    /**
     * 场景
     */
    private final Integer scene;
    /**
     * 场景名
     */
    private final String name;
    /**
     * 基础包名
     */
    private final String basePackage;
    /**
     * Controller 和 VO 类的前缀
     */
    private final String prefixClass;

    public static CodegenSceneEnum valueOf(Integer scene) {
        return firstMatch(sceneEnum -> sceneEnum.getScene().equals(scene), values());
    }

    /**
     * 是否为管理后台场景。
     *
     * @param scene 场景
     * @return 是否为管理后台场景
     */
    public static boolean isAdmin(Integer scene) {
        return ObjectUtil.equal(scene, ADMIN.scene);
    }

    /**
     * 是否为支持的生成场景。
     *
     * @param scene 场景
     * @return 是否支持
     */
    public static boolean isSupported(Integer scene) {
        return ObjectUtil.equal(scene, ADMIN.scene) || ObjectUtil.equal(scene, APP.scene);
    }
}
