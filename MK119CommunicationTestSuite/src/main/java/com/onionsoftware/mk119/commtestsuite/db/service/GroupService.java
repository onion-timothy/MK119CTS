package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Group;
import com.onionsoftware.mk119.commtestsuite.db.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GroupService {
    private final GroupRepository groupRepository;

    public void makeRootGroup(){
        if(groupRepository.count() == 0){
            Group root = new Group();
            root.setParentId(0);
            root.setName("ROOT");
            groupRepository.save(root);
        }
    }

    public List<Group> selectGroups() {
        return groupRepository.findAll();
    }

    public List<Group> selectGroupsByParentGroupId(Integer parent_id) {
        return groupRepository.findGroupsByParentId(parent_id);
    }

    public void insertGroup(Group group) {
        groupRepository.save(group);
    }

    public void deleteGroup(Integer id) {
        groupRepository.deleteById(id);
    }

    public void renameGroup(Group group) {
        groupRepository.save(group);
    }
}
