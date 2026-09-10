package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysFileInfo;
import com.unisence.iot.admin.system.vo.FileInfoVO;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-13T14:53:40+0800",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.9 (Arch Linux)"
)
@Component
public class SysFileInfoConverterImpl implements SysFileInfoConverter {

    @Override
    public FileInfoVO toVO(SysFileInfo entity) {
        if ( entity == null ) {
            return null;
        }

        FileInfoVO fileInfoVO = new FileInfoVO();

        fileInfoVO.setFileInfoId( entity.getFileInfoId() );
        fileInfoVO.setFileName( entity.getFileName() );
        fileInfoVO.setBucketName( entity.getBucketName() );
        fileInfoVO.setObjectName( entity.getObjectName() );
        fileInfoVO.setFileSize( entity.getFileSize() );
        fileInfoVO.setFileSuffix( entity.getFileSuffix() );
        fileInfoVO.setFileUrl( entity.getFileUrl() );
        fileInfoVO.setCreateBy( entity.getCreateBy() );
        fileInfoVO.setCreateTime( entity.getCreateTime() );

        return fileInfoVO;
    }
}
