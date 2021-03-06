package org.btrplace.scheduler.kherbacheThesis.energy;

import net.minidev.json.JSONObject;
import org.btrplace.json.JSONConverterException;
import org.btrplace.json.plan.ReconfigurationPlanConverter;
import org.btrplace.model.*;
import org.btrplace.model.constraint.Fence;
import org.btrplace.model.constraint.MinMTTR;
import org.btrplace.model.constraint.Offline;
import org.btrplace.model.constraint.SatConstraint;
import org.btrplace.model.view.ShareableResource;
import org.btrplace.model.view.net.NetworkView;
import org.btrplace.model.view.net.Switch;
import org.btrplace.model.view.power.EnergyView;
import org.btrplace.plan.ReconfigurationPlan;
import org.btrplace.scheduler.SchedulerException;
import org.btrplace.scheduler.choco.DefaultChocoScheduler;
import org.btrplace.scheduler.choco.DefaultParameters;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Vincent Kherbache
 */
public class BtrPlace {

    String path = new File("").getAbsolutePath() +
            "/src/test/java/org/btrplace/scheduler/kherbacheThesis/energy/";

    @Test
    public void energyTest() throws SchedulerException {

        // Set nb of nodes and vms
        int nbNodesRack = 24;
        int nbSrcNodes = nbNodesRack * 2;
        int nbDstNodes = nbNodesRack * 1;
        int nbVMs = nbSrcNodes * 2;

        // Set mem + cpu for VMs and Nodes
        int memVM = 4, cpuVM = 1;
        int memSrcNode = 16, cpuSrcNode = 4;
        int memDstNode = 16, cpuDstNode = 4;

        // Set memoryUsed and dirtyRate (for all VMs)
        int tpl1MemUsed = 2000, tpl1MaxDirtySize = 5, tpl1MaxDirtyDuration = 3;
        double tpl1DirtyRate = 0; // idle vm
        int tpl2MemUsed = 4000, tpl2MaxDirtySize = 96, tpl2MaxDirtyDuration = 2;
        double tpl2DirtyRate = 3; // stress --vm 1000 --bytes 70K
        int tpl3MemUsed = 2000, tpl3MaxDirtySize = 96, tpl3MaxDirtyDuration = 2;
        double tpl3DirtyRate = 3; // stress --vm 1000 --bytes 70K
        int tpl4MemUsed = 4000, tpl4MaxDirtySize = 5, tpl4MaxDirtyDuration = 3;
        double tpl4DirtyRate = 0; // idle vm

        // New default model
        Model mo = new DefaultModel();
        Mapping ma = mo.getMapping();

        // Create online source nodes and offline destination nodes
        List<Node> srcNodes = new ArrayList<>(), dstNodes = new ArrayList<>();
        for (int i = 0; i < nbSrcNodes; i++) {
            srcNodes.add(mo.newNode());
            ma.addOnlineNode(srcNodes.get(i));
        }
        for (int i = 0; i < nbDstNodes; i++) {
            dstNodes.add(mo.newNode());
            ma.addOfflineNode(dstNodes.get(i));
        }

        // Set boot and shutdown time
        for (Node n : dstNodes) {
            mo.getAttributes().put(n, "boot", 120); /*~2 minutes to boot*/
        }
        for (Node n : srcNodes) {
            mo.getAttributes().put(n, "shutdown", 17); /*~30 seconds to shutdown*/
        }

        // Create running VMs on src nodes
        List<VM> vms = new ArrayList<>();
        VM v;
        for (int i = 0; i < nbSrcNodes; i++) {
            if (i % 2 == 0) {
                v = mo.newVM();
                vms.add(v);
                mo.getAttributes().put(v, "memUsed", tpl1MemUsed);
                mo.getAttributes().put(v, "dirtyRate", tpl1DirtyRate);
                mo.getAttributes().put(v, "maxDirtySize", tpl1MaxDirtySize);
                mo.getAttributes().put(v, "maxDirtyDuration", tpl1MaxDirtyDuration);
                ma.addRunningVM(v, srcNodes.get(i));
                v = mo.newVM();
                vms.add(v);
                mo.getAttributes().put(v, "memUsed", tpl2MemUsed);
                mo.getAttributes().put(v, "dirtyRate", tpl2DirtyRate);
                mo.getAttributes().put(v, "maxDirtySize", tpl2MaxDirtySize);
                mo.getAttributes().put(v, "maxDirtyDuration", tpl2MaxDirtyDuration);
                ma.addRunningVM(v, srcNodes.get(i));
            } else {
                v = mo.newVM();
                vms.add(v);
                mo.getAttributes().put(v, "memUsed", tpl3MemUsed);
                mo.getAttributes().put(v, "dirtyRate", tpl3DirtyRate);
                mo.getAttributes().put(v, "maxDirtySize", tpl3MaxDirtySize);
                mo.getAttributes().put(v, "maxDirtyDuration", tpl3MaxDirtyDuration);
                ma.addRunningVM(v, srcNodes.get(i));
                v = mo.newVM();
                vms.add(v);
                mo.getAttributes().put(v, "memUsed", tpl4MemUsed);
                mo.getAttributes().put(v, "dirtyRate", tpl4DirtyRate);
                mo.getAttributes().put(v, "maxDirtySize", tpl4MaxDirtySize);
                mo.getAttributes().put(v, "maxDirtyDuration", tpl4MaxDirtyDuration);
                ma.addRunningVM(v, srcNodes.get(i));
            }
        }

        // Add resource views
        ShareableResource rcMem = new ShareableResource("mem", 0, 0);
        ShareableResource rcCPU = new ShareableResource("cpu", 0, 0);
        for (Node n : srcNodes) {
            rcMem.setCapacity(n, memSrcNode);
            rcCPU.setCapacity(n, cpuSrcNode);
        }
        for (Node n : dstNodes) {
            rcMem.setCapacity(n, memDstNode);
            rcCPU.setCapacity(n, cpuDstNode);
        }
        for (VM vm : vms) {
            rcMem.setConsumption(vm, memVM);
            rcCPU.setConsumption(vm, cpuVM);
        }
        mo.attach(rcMem);
        mo.attach(rcCPU);

        // Add the EnergyView and set nodes & vms consumption
        int powerIdleNode = 110, powerVM = 16, powerBoot = 20;  // Define power values
        EnergyView energyView = new EnergyView(Integer.MAX_VALUE);
        energyView.setBootOverhead(powerBoot); // % energy overhead during boot
        for (Node n : srcNodes) {
            energyView.setConsumption(n, powerIdleNode);
        }
        for (Node n : dstNodes) {
            energyView.setConsumption(n, powerIdleNode);
        }
        for (VM vm : vms) {
            energyView.setConsumption(vm, powerVM);
        }
        mo.attach(energyView);

        // Add a NetworkView view
        NetworkView net = new NetworkView();
        Switch swSrcRack1 = net.newSwitch();
        Switch swSrcRack2 = net.newSwitch();
        Switch swDstRack1 = net.newSwitch();
        Switch swMain = net.newSwitch();
        swSrcRack1.connect(1000, srcNodes.subList(0, nbNodesRack));
        swSrcRack2.connect(1000, srcNodes.subList(nbNodesRack, nbNodesRack * 2));
        swDstRack1.connect(1000, dstNodes.subList(0, nbNodesRack));
        swMain.connect(10000, swSrcRack1, swSrcRack2, swDstRack1);
        mo.attach(net);
        //net.generateDot(path + "topology.dot", false);

        // Set parameters
        DefaultParameters ps = new DefaultParameters();
        ps.setVerbosity(0);
        ps.setTimeLimit(10);
        //ps.setMaxEnd(600);
        ps.doOptimize(false);

        // Migrate all VMs to destination nodes
        List<SatConstraint> cstrs = new ArrayList<>();
        int vm_num = 0;
        for (int i = 0; i < nbDstNodes; i++) {
            cstrs.add(new Fence(vms.get(vm_num), Collections.singleton(dstNodes.get(i))));
            cstrs.add(new Fence(vms.get(vm_num + 1), Collections.singleton(dstNodes.get(i))));
            cstrs.add(new Fence(vms.get(nbVMs - 1 - vm_num), Collections.singleton(dstNodes.get(i))));
            cstrs.add(new Fence(vms.get(nbVMs - 2 - vm_num), Collections.singleton(dstNodes.get(i))));
            vm_num += 2;
        }

        // Shutdown source nodes
        for (Node n : srcNodes) {
            cstrs.add(new Offline(n));
        }

        // Set the objective
        DefaultChocoScheduler sc = new DefaultChocoScheduler(ps);
        Instance i = new Instance(mo, cstrs,  new MinMTTR());

        ReconfigurationPlan p;
        try {
            p = sc.solve(i);
            Assert.assertNotNull(p);

            ReconfigurationPlanConverter planConverter = new ReconfigurationPlanConverter();
            JSONObject obj = null;
            try {
                obj = planConverter.toJSON(p);
            } catch (JSONConverterException e) {
                System.err.println("Error while converting plan: " + e.toString());
                //e.printStackTrace();
            }
            try {
                FileWriter file = new FileWriter(path + "BtrPlace.json");
                file.write(obj.toJSONString());
                file.flush();
                file.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            //energyView.plotConsumption(p, path + "BtrPlace_theory.csv");
            
        } finally {
            System.err.println(sc.getStatistics());
            System.err.flush();
        }
    }
}
