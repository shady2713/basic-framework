package com.basicframework.module.infra.dal.mysql.file;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 文件操作 Mapper
 *
 */
@Mapper
public interface FileMapper extends BaseMapperX<FileDO> {

    default PageResult<FileDO> selectPage(PageParam pageParam, String path, String type, LocalDateTime[] createTime) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<FileDO>()
                        .likeIfPresent(FileDO::getPath, path)
                        .likeIfPresent(FileDO::getType, type)
                        .betweenIfPresent(FileDO::getCreateTime, createTime)
                        .orderByDesc(FileDO::getId));
    }

    default long selectCountByConfigId(Long configId) {
        return selectCount(FileDO::getConfigId, configId);
    }

    @Select(
            """
            SELECT COUNT(*)
            FROM infra_file f
            LEFT JOIN infra_file_config c ON c.id = f.config_id AND c.deleted = b'0'
            WHERE f.config_id IS NOT NULL AND c.id IS NULL
            """)
    int selectOrphanFileConfigCount();
}
