package com.onionsoftware.mk119.commtestsuite.db.repository;

import com.onionsoftware.mk119.commtestsuite.db.bean.ProtocolDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProtocolDefinitionRepository extends JpaRepository<ProtocolDefinition, Integer> {
    Optional<ProtocolDefinition> findByName(String name);
    boolean existsByName(String name);
    /** 이름을 바꿀 때 자기 자신을 빼고 중복을 본다 */
    boolean existsByNameAndIdNot(String name, Integer id);
    List<ProtocolDefinition> findAllByOrderByNameAsc();
}
