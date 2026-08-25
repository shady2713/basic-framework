package com.basicframework.module.system.controller.admin.permission;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.security.core.annotation.MfaStepUp;
import com.basicframework.module.system.controller.admin.permission.vo.menu.MenuListReqVO;
import com.basicframework.module.system.controller.admin.permission.vo.menu.MenuRespVO;
import com.basicframework.module.system.controller.admin.permission.vo.menu.MenuSaveVO;
import com.basicframework.module.system.controller.admin.permission.vo.menu.MenuSimpleRespVO;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.service.permission.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "管理后台 - 菜单")
@RestController
@RequestMapping("/system/menu")
@Validated
public class MenuController {

    @Resource
    private MenuService menuService;

    @PostMapping("/create")
    @Operation(summary = "创建菜单")
    @PreAuthorize("@ss.hasPermission('system:menu:create')")
    @MfaStepUp
    public CommonResult<Long> createMenu(@Valid @RequestBody MenuSaveVO createReqVO) {
        Long menuId = menuService.createMenu(BeanUtils.toBean(createReqVO, MenuDO.class));
        return success(menuId);
    }

    @PutMapping("/update")
    @Operation(summary = "修改菜单")
    @PreAuthorize("@ss.hasPermission('system:menu:update')")
    @MfaStepUp
    public CommonResult<Boolean> updateMenu(@Valid @RequestBody MenuSaveVO updateReqVO) {
        menuService.updateMenu(BeanUtils.toBean(updateReqVO, MenuDO.class));
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除菜单")
    @Parameter(name = "id", description = "菜单编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:menu:delete')")
    @MfaStepUp
    public CommonResult<Boolean> deleteMenu(@RequestParam("id") Long id) {
        menuService.deleteMenu(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除菜单")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('system:menu:delete')")
    @MfaStepUp
    public CommonResult<Boolean> deleteMenuList(@RequestParam("ids") List<Long> ids) {
        menuService.deleteMenuList(ids);
        return success(true);
    }

    @GetMapping("/list")
    @Operation(summary = "获取菜单列表", description = "用于【菜单管理】界面")
    @PreAuthorize("@ss.hasPermission('system:menu:query')")
    public CommonResult<List<MenuRespVO>> getMenuList(MenuListReqVO reqVO) {
        List<MenuDO> list = menuService.getMenuList(reqVO.getName(), reqVO.getStatus());
        list.sort(Comparator.comparing(MenuDO::getSort));
        return success(BeanUtils.toBean(list, MenuRespVO.class));
    }

    @GetMapping({"/list-all-simple", "simple-list"})
    @Operation(summary = "获取菜单精简信息列表", description = "只包含被开启的菜单，用于【角色分配菜单】功能的选项。")
    public CommonResult<List<MenuSimpleRespVO>> getSimpleMenuList() {
        List<MenuDO> list = menuService.getMenuListFiltered(null, CommonStatusEnum.ENABLE.getStatus());
        list = menuService.filterDisableMenus(list);
        list.sort(Comparator.comparing(MenuDO::getSort));
        return success(BeanUtils.toBean(list, MenuSimpleRespVO.class));
    }

    @GetMapping("/get")
    @Operation(summary = "获取菜单信息")
    @PreAuthorize("@ss.hasPermission('system:menu:query')")
    public CommonResult<MenuRespVO> getMenu(Long id) {
        MenuDO menu = menuService.getMenu(id);
        return success(BeanUtils.toBean(menu, MenuRespVO.class));
    }
}
