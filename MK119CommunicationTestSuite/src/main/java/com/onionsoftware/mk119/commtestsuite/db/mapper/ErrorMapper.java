package com.onionsoftware.mk119.commtestsuite.db.mapper;

import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Repository
@Mapper
public interface ErrorMapper {
    void createScheme();
    Error selectErrorByDeviceId(Integer device_id);
    void insertError(Error error);
    void updateError(Error error);
    void deleteError(Integer device_id);
}
