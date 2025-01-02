package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.repository.ErrorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.persistence.EntityNotFoundException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ErrorService {
    final ErrorRepository errorRepository;

    public List<Error> selectErrorsByDeviceId(Integer device_id) {
        return errorRepository.findErrorsByDeviceId(device_id);
    }

    public List<Error> selectEnabledErrorsByDeviceId(Integer device_id){
        return errorRepository.findErrorsByDeviceIdAndEnabled(device_id, Error.ERROR_ENABLED);
    }

    public Error selectErrorById(Integer error_id){
        return errorRepository.findById(error_id).orElseThrow(EntityNotFoundException::new);
    }

    public void insertError(Error error) {
        errorRepository.save(error);
    }

    public void updateError(Error error) {
        errorRepository.save(error);
    }

    public void deleteError(Integer device_id) {
        errorRepository.deleteById(device_id);
    }

    public boolean getErrorEnabled(Integer id) {
        return errorRepository.findById(id).orElseThrow(EntityNotFoundException::new).getEnabled() == Error.ERROR_ENABLED;
    }
}
