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

    /**
     * 이름만 바꾼다. 화면은 {id, name} 만 보내오므로 받은 객체를 그대로 저장하면
     * parentId 가 null 로 덮여 트리 계층이 끊긴다. 저장된 행을 읽어 이름만 올린다.
     */
    public void renameGroup(Group group) {
        if (group == null || group.getId() == null) return;
        Group stored = groupRepository.findById(group.getId()).orElse(null);
        if (stored == null) return;
        stored.setName(group.getName());
        groupRepository.save(stored);
    }
}
