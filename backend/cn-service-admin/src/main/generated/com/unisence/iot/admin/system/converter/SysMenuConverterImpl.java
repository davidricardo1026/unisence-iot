package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysMenu;
import com.unisence.iot.admin.system.vo.MenuTreeVO;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-13T14:53:40+0800",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.9 (Arch Linux)"
)
@Component
public class SysMenuConverterImpl implements SysMenuConverter {

    @Override
    public MenuTreeVO toTreeNode(SysMenu menu) {
        if ( menu == null ) {
            return null;
        }

        MenuTreeVO menuTreeVO = new MenuTreeVO();

        menuTreeVO.setMenuId( menu.getMenuId() );
        menuTreeVO.setParentId( menu.getParentId() );
        menuTreeVO.setMenuName( menu.getMenuName() );
        menuTreeVO.setPath( menu.getPath() );
        menuTreeVO.setComponent( menu.getComponent() );
        menuTreeVO.setPerms( menu.getPerms() );
        menuTreeVO.setIcon( menu.getIcon() );
        menuTreeVO.setSortOrder( menu.getSortOrder() );
        menuTreeVO.setIsVisible( menu.getIsVisible() );
        menuTreeVO.setMenuType( menu.getMenuType() );
        menuTreeVO.setModuleId( menu.getModuleId() );
        menuTreeVO.setVersion( menu.getVersion() );
        menuTreeVO.setCreateTime( menu.getCreateTime() );

        return menuTreeVO;
    }
}
