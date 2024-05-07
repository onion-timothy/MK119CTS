package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.mapper.ErrorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ErrorService {
    private final ErrorMapper errorMapper;

    public void createScheme() {
        errorMapper.createScheme();
    }

    public Error selectErrorByDeviceId(Integer device_id) {
        return errorMapper.selectErrorByDeviceId(device_id);
    }

    public void insertError(Error error) {
        errorMapper.insertError(error);
    }

    public void updateError(Error error) {
        errorMapper.updateError(error);
    }

    public void deleteError(Integer device_id) {
        errorMapper.deleteError(device_id);
    }
}
