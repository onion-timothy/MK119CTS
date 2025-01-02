package com.onionsoftware.mk119.commtestsuite.db.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import java.util.List;

public interface ErrorRepository extends JpaRepository<Error, Integer> {
    List<Error> findErrorsByDeviceId(Integer device_id);
    List<Error> findErrorsByDeviceIdAndEnabled(Integer device_id, Integer enabled);
}
