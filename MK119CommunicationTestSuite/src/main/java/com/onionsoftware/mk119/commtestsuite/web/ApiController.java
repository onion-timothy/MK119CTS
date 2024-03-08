package com.onionsoftware.mk119.commtestsuite.web;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Group;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.GroupService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.*;

@RestController
@RequestMapping(value = "/api")
public class ApiController {
    private final DeviceService deviceService;
    private final GroupService groupService;
    private final SimulationService simulationService;

    @Autowired
    public ApiController(DeviceService deviceService, GroupService groupService, SimulationService simulationService){
        this.deviceService = deviceService;
        this.groupService = groupService;
        this.simulationService = simulationService;
    }

    @RequestMapping(value = "/device/devices/{group_id}", method = RequestMethod.GET)
    public List<Device> getDevicesByGroupId(@PathVariable Integer group_id) {
        return deviceService.selectDevicesByGroupId(group_id);
    }

    @RequestMapping(value = "/device/devices/treeData", method = RequestMethod.GET)
    public List<Map<?, ?>> getDevicesTreeData(@RequestParam Integer id) {
        List<Group> groups = groupService.selectGroupsByParentGroupId(id);
        List<Device> devices = deviceService.selectDevicesByGroupId(id);

        List<Map<?, ?>> ret = new LinkedList<>();
        for (Group group : groups) {
            Map<Object, Object> groupObj = new HashMap<>();
            groupObj.put("id", "g_" + group.getId());
            groupObj.put("parent", group.getParent_id() == 0 ? "#" : "g_" + group.getParent_id());
            groupObj.put("text", group.getName());
            groupObj.put("children", true);
            groupObj.put("type", "group");
            ret.add(groupObj);
        }

        for (Device device : devices) {
            Map<Object, Object> deviceObj = new HashMap<>();
            deviceObj.put("id", "d_" + device.getId());
            deviceObj.put("parent", device.getGroup_id() == 0 ? "#" : "g_" + device.getGroup_id());
            deviceObj.put("text", device.getName());
            deviceObj.put("children", false);
            deviceObj.put("type", "device");
            ret.add(deviceObj);
        }
        return ret;
    }

    @RequestMapping(value = "/group/groups/treeData", method = RequestMethod.GET)
    public List<Map<?, ?>> getGroupsTreeData() {
        List<Group> groups = groupService.selectGroups();

        List<Map<?, ?>> ret = new LinkedList<>();
        for (Group group : groups) {
            Map<Object, Object> groupObj = new HashMap<>();
            groupObj.put("id", "g_" + group.getId());
            groupObj.put("parent", group.getParent_id() == 0 ? "#" : "g_" + group.getParent_id());
            groupObj.put("text", group.getName());
            groupObj.put("type", "group");
            ret.add(groupObj);
        }
        return ret;
    }

    @RequestMapping(value = "/device/{id}")
    public Device getDevice(@PathVariable Integer id) {
        return deviceService.selectDevice(id);
    }

    @RequestMapping(value = "/group/addGroup", method = RequestMethod.PUT)
    public Group addGroup(@RequestBody Group group) {
        groupService.insertGroup(group);
        return group;
    }

    @RequestMapping(value = "/group/deleteGroup/{id}", method = RequestMethod.DELETE)
    public Integer deleteGroup(@PathVariable Integer id) {
        groupService.deleteGroup(id);
        return id;
    }

    @RequestMapping(value = "/group/renameGroup", method = RequestMethod.POST)
    public Group renameGroup(@RequestBody Group group) {
        groupService.renameGroup(group);
        return group;
    }

    @RequestMapping(value = "/device/addDevice", method = RequestMethod.PUT)
    public Device renameGroup(@RequestBody Device device) {
        deviceService.insertDevice(device);
        return device;
    }

    @RequestMapping(value = "/device/deleteDevice/{id}", method = RequestMethod.DELETE)
    public Integer deleteDevice(@PathVariable Integer id) {
        deviceService.deleteDevice(id);
        return id;
    }

    @RequestMapping(value = "/device/updateDevice", method = RequestMethod.POST)
    public Device updateDevice(@RequestBody Device device) {
        deviceService.updateDevice(device);
        return device;
    }

    @RequestMapping(value = "/device/getEnabled/{id}", method = RequestMethod.GET)
    public Boolean selectDeviceEnabled(@PathVariable Integer id) {
        return deviceService.selectDeviceEnabled(id);
    }

    @RequestMapping(value = "/device/setEnabled/{id}/{enabled}", method = RequestMethod.POST)
    public Integer updateDeviceEnabled(@PathVariable Integer id, @PathVariable Integer enabled) {
        deviceService.updateDeviceEnabled(enabled, id);
        return enabled;
    }

    @RequestMapping(value = "/device/devices", method = RequestMethod.GET)
    public List<Device> getDevices() {
        return deviceService.selectDevices();
    }

    @RequestMapping(value = "/simulation/simulations/{deviceId}")
    public List<Simulation> getSimulationsByDeviceId(@PathVariable Integer deviceId) {
        return simulationService.selectSimulationsByDeviceId(deviceId);
    }

    @RequestMapping(value = "/simulation/addSimulation", method = RequestMethod.PUT)
    public Simulation addSimulation(@RequestBody Simulation simulation) {
        simulationService.insertSimulation(simulation);
        return simulation;
    }

    @RequestMapping(value = "/simulation/updateSimulation", method = RequestMethod.POST)
    public Simulation updateSimulation(@RequestBody Simulation simulation) {
        simulationService.updateSimulation(simulation);
        return simulation;
    }

    @RequestMapping(value = "/simulation/deleteSimulation/{id}", method = RequestMethod.DELETE)
    public Integer deleteSimulation(@PathVariable Integer id) {
        simulationService.deleteSimulation(id);
        return id;
    }

    @RequestMapping(value = "/simulation/getEnabled/{id}", method = RequestMethod.GET)
    public Boolean selectSimulationEnabled(@PathVariable Integer id) {
        return simulationService.selectSimulationEnabled(id);
    }

    @RequestMapping(value = "/simulation/setEnabled/{id}/{enabled}", method = RequestMethod.POST)
    public Integer updateSimulationEnabled(@PathVariable Integer id, @PathVariable Integer enabled) {
        Simulation simulation = simulationService.selectSimulation(id);
        simulation.setEnabled(enabled);
        simulationService.updateSimulation(simulation);
        return enabled;
    }

    @ExceptionHandler({SQLException.class})
    public ResponseEntity<String> handleDBError(Exception e) {
        return new ResponseEntity<>("DB Error. " + e.getMessage(), new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
