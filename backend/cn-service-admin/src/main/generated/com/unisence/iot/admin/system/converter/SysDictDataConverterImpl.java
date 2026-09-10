package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysDictData;
import com.unisence.iot.admin.system.dto.DictDataCreateRequest;
import com.unisence.iot.admin.system.dto.DictDataUpdateRequest;
import com.unisence.iot.admin.system.vo.DictDataVO;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-13T14:53:40+0800",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.9 (Arch Linux)"
)
@Component
public class SysDictDataConverterImpl implements SysDictDataConverter {

    @Override
    public DictDataVO toVO(SysDictData data) {
        if ( data == null ) {
            return null;
        }

        DictDataVO dictDataVO = new DictDataVO();

        dictDataVO.setDictDataId( data.getDictDataId() );
        dictDataVO.setSortOrder( data.getSortOrder() );
        dictDataVO.setDictLabel( data.getDictLabel() );
        dictDataVO.setDictValue( data.getDictValue() );
        dictDataVO.setDictType( data.getDictType() );
        dictDataVO.setStatus( data.getStatus() );
        dictDataVO.setVersion( data.getVersion() );
        dictDataVO.setCreateTime( data.getCreateTime() );

        return dictDataVO;
    }

    @Override
    public SysDictData toEntity(DictDataCreateRequest request) {
        if ( request == null ) {
            return null;
        }

        SysDictData sysDictData = new SysDictData();

        sysDictData.setSortOrder( request.getSortOrder() );
        sysDictData.setDictLabel( request.getDictLabel() );
        sysDictData.setDictValue( request.getDictValue() );
        sysDictData.setDictType( request.getDictType() );
        sysDictData.setStatus( request.getStatus() );

        return sysDictData;
    }

    @Override
    public void updateEntity(SysDictData entity, DictDataUpdateRequest request) {
        if ( request == null ) {
            return;
        }

        entity.setSortOrder( request.getSortOrder() );
        entity.setDictLabel( request.getDictLabel() );
        entity.setDictValue( request.getDictValue() );
        entity.setStatus( request.getStatus() );
    }
}
