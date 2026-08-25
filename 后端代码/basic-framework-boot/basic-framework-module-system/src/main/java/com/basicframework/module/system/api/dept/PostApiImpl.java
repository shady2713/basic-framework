package com.basicframework.module.system.api.dept;

import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.module.system.api.dept.dto.PostRespDTO;
import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.service.dept.PostService;
import jakarta.annotation.Resource;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 岗位 API 实现类
 *
 */
@Service
public class PostApiImpl implements PostApi {

    @Resource
    private PostService postService;

    @Override
    public void validPostList(Collection<Long> ids) {
        postService.validatePostList(ids);
    }

    @Override
    public List<PostRespDTO> getPostList(Collection<Long> ids) {
        List<PostDO> list = postService.getPostList(ids);
        return BeanUtils.toBean(list, PostRespDTO.class);
    }
}
