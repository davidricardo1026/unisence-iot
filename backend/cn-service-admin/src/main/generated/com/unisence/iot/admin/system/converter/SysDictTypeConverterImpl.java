package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysDictType;
import com.unisence.iot.admin.system.dto.DictTypeCreateRequest;
import com.unisence.iot.admin.system.dto.DictTypeUpdateRequest;
import com.unisence.iot.admin.system.vo.DictTypeVO;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-13T14:53:40+0800",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.9 (Arch Linux)"
)
@Component
public class SysDictTypeConverterImpl implements SysDictTypeConverter {

    @Override
    public DictTypeVO toVO(SysDictType type) {
        if ( type == null ) {
            return null;
        }

        DictTypeVO dictTypeVO = new DictTypeVO();

        dictTypeVO.setDictTypeId( type.getDictTypeId() );
        dictTypeVO.setDictName( type.getDictName() );
        dictTypeVO.setDictType( type.getDictType() );
        dictTypeVO.setStatus( type.getStatus() );
        dictTypeVO.setVersion( type.getVersion() );
        dictTypeVO.setCreateTime( type.getCreateTime() );

        return dictTypeVO;
    }

    @Override
    public SysDictType toEntity(DictTypeCreateRequest request) {
        if ( request == null ) {
            return null;
        }

        SysDictType sysDictType = new SysDictType();

        sysDictType.setDictName( request.getDictName() );
        sysDictType.setDictType( request.getDictType() );
        sysDictType.setStatus( request.getStatus() );

        return sysDictType;
    }

    @Override
    public void updateEntity(SysDictType entity, DictTypeUpdateRequest request) {
        if ( request == null ) {
            return;
        }

        entity.setDictName( request.getDictName() );
        entity.setDictType( request.getDictType() );
        entity.setStatus( request.getStatus() );
    }
}
