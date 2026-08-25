package com.basicframework.module.infra.service.codegen;

import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenColumnDO;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenTableDO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 代码生成 Service 接口
 *
 */
public interface CodegenService {

    /**
     * 基于数据库的表结构，创建代码生成器的表定义
     *
     * @param author     作者
     * @param tableNames 表名数组
     * @return 创建的表定义的编号数组
     */
    List<Long> createCodegenList(String author, List<String> tableNames);

    /**
     * 更新数据库的表和字段定义
     *
     * @param table   表定义
     * @param columns 字段定义数组
     */
    void updateCodegen(CodegenTableDO table, List<CodegenColumnDO> columns);

    /**
     * 基于数据库的表结构，同步数据库的表和字段定义
     *
     * @param tableId 表编号
     */
    void syncCodegenFromDB(Long tableId);

    /**
     * 删除数据库的表和字段定义
     *
     * @param tableId 数据编号
     */
    void deleteCodegen(Long tableId);

    /**
     * 批量删除数据库的表和字段定义
     *
     * @param tableIds 数据编号列表
     */
    void deleteCodegenList(List<Long> tableIds);

    /**
     * 获得表定义列表
     *
     * @return 表定义列表
     */
    List<CodegenTableDO> getCodegenTableList();

    /**
     * 获得表定义分页
     *
     * @param pageParam    分页参数
     * @param tableName    表名称，模糊匹配
     * @param tableComment 表描述，模糊匹配
     * @param className    类名称，模糊匹配
     * @param createTime   创建时间区间
     * @return 表定义分页
     */
    PageResult<CodegenTableDO> getCodegenTablePage(
            PageParam pageParam, String tableName, String tableComment, String className, LocalDateTime[] createTime);

    /**
     * 获得表定义
     *
     * @param id 表编号
     * @return 表定义
     */
    CodegenTableDO getCodegenTable(Long id);

    /**
     * 获得指定表的字段定义数组
     *
     * @param tableId 表编号
     * @return 字段定义数组
     */
    List<CodegenColumnDO> getCodegenColumnListByTableId(Long tableId);

    /**
     * 执行指定表的代码生成
     *
     * @param tableId 表编号
     * @return 生成结果。key 为文件路径，value 为对应的代码内容
     */
    Map<String, String> generationCodes(Long tableId);

    /**
     * 获得数据库自带的表定义列表
     *
     * @param name               表名称
     * @param comment            表描述
     * @return 表定义列表
     */
    List<TableInfo> getDatabaseTableList(String name, String comment);
}
