package com.onionsoftware.mk119.commtestsuite.web;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Group;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.GroupService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.MkDecoder;
import com.onionsoftware.mk119.commtestsuite.web.bean.Session;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping(value = "/api")
@RequiredArgsConstructor
public class ApiController {
    private final DeviceService deviceService;
    private final GroupService groupService;
    private final SimulationService simulationService;
    private final IOServerInstances ioServerInstances;
    private final Logger logger = LoggerFactory.getLogger("api");

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

    @RequestMapping(value = "/socketTest", method = RequestMethod.GET)
    public Boolean socketTest(@RequestParam("host_transport") String host_transport, @RequestParam("port") Integer port) throws IOException {
        String[] tokens = host_transport.split("_");
        String host = tokens[0];
        if (host.equals("0.0.0.0")) host = "\\*";
        String transport = tokens[1];

        try {
            if (SystemUtils.IS_OS_MAC) {
                String regex = transport.toLowerCase() + ".+" + host + "\\." + port + ".+";
                Process netstat = Runtime.getRuntime().exec(new String[]{
                        "/bin/sh",
                        "-c",
                        "netstat -anv | grep LISTEN"});
                BufferedReader inStream = new BufferedReader(new InputStreamReader(netstat.getInputStream()));
                List<String> lines = inStream.lines().collect(Collectors.toList());
                for(String line : lines){
                    if(Pattern.matches(regex, line)){
                        return true;
                    }
                }
                return false;
            } else if (SystemUtils.IS_OS_WINDOWS) {
                // TODO IF RUN ON WINDOWS
                return false;
            } else return false;
        } catch (IOException e) {
            logger.error("Runtime exec error.", e);
            return false;
        }
    }

    @RequestMapping(value="/sessions/{id}", method=RequestMethod.GET)
    public Map<SocketAddress, Long> sessions(@PathVariable("id") Integer id){
        IOServer server = ioServerInstances.getServers().get(id);
        List<Session> sessions = new ArrayList<>();
        for(InetSocketAddress remote : server.getSessions().keySet()){
            Session session = new Session();
            session.setRemoteHost(remote.getHostName());
            session.setRemotePort(remote.getPort());
            sessions.add(session);
        }
        return null;
    }

    @ExceptionHandler({SQLException.class})
    public ResponseEntity<String> handleDBError(Exception e) {
        return new ResponseEntity<>("DB Error. " + e.getMessage(), new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
