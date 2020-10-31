/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.bridge;

import com.google.common.collect.Maps;
import com.google.common.collect.ImmutableSet;
import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;

import java.util.Dictionary;
import java.util.Properties;
import java.util.Map;
import java.util.Optional;

import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
           service = {SomeInterface.class},
           property = {
               "someProperty=Some Default String Value",
           })
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** Some configurable property. */
    private String someProperty;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected ComponentConfigService cfgService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowRuleService flowRuleService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected CoreService coreService;

	protected Map<DeviceId, Map<MacAddress,PortNumber>> macTable =Maps.newConcurrentMap();
//	private ReactivePacketProcessor processor = new ReactivePacketProcessor();
	private PacketProcessor processor = new SwitchPacketProcessor();
	private ApplicationId appId;


    @Activate
    protected void activate() {
		cfgService.registerProperties(getClass());
        log.info("Started");
		appId = coreService.getAppId("nctu.winlab.bridge");
		log.info("STTTTTTTTART");
		packetService.addProcessor(processor,PacketProcessor.director(1));

		packetService.requestPackets(DefaultTrafficSelector.builder()
			.matchEthType(Ethernet.TYPE_IPV4).build(),PacketPriority.REACTIVE,appId,Optional.empty());
		packetService.requestPackets(DefaultTrafficSelector.builder()
			.matchEthType(Ethernet.TYPE_ARP).build(),PacketPriority.REACTIVE,appId,Optional.empty());

    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
		packetService.removeProcessor(processor);
		flowRuleService.removeFlowRulesById(appId);
		processor = null;
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured");
    }
	private class SwitchPacketProcessor implements PacketProcessor{
//	private class ReactivePacketProcessor implements PacketProcessor{
		@Override
		public void process(PacketContext packetContext){
			macTable.putIfAbsent(packetContext.inPacket().receivedFrom().deviceId(), Maps.newConcurrentMap());
			lookUpAndFlowMod(packetContext);
		}
		public void floodFunc(PacketContext packetContext){
			packetContext.treatmentBuilder().setOutput(PortNumber.FLOOD);
			packetContext.send();
		}
		public void lookUpAndFlowMod(PacketContext packetContext){
			Short type = packetContext.inPacket().parsed().getEtherType();
			if(type!=Ethernet.TYPE_IPV4 && type != Ethernet.TYPE_ARP){
				return;
			}

			ConnectPoint connectPoint = packetContext.inPacket().receivedFrom();
			Map<MacAddress, PortNumber>temp_mactable = macTable.get(connectPoint.deviceId());//get specific switch
			MacAddress srcMac = packetContext.inPacket().parsed().getSourceMAC();
			MacAddress dstMac = packetContext.inPacket().parsed().getDestinationMAC();
//			if(temp_mactable.get(srcMac) == null || temp_mactable.get(srcMac) != connectPoint.port()){
			if(temp_mactable.get(srcMac) == null){
				String deviceString = connectPoint.deviceId().toString();
				String portString = connectPoint.port().toString();
				String macString = srcMac.toString();
				String msgString = "Add MAC address ==> switch: of:"+deviceString+", MAC: "+macString+", port: "+portString;
				log.info(msgString);

			}
			temp_mactable.put(srcMac, connectPoint.port());//record the port with source mac address
			PortNumber outputPort = temp_mactable.get(dstMac);

			//destination mac in the table and send flowmod
			if(outputPort != null){
				//if packet type is arp then dont need to install rule
				if(type==Ethernet.TYPE_IPV4){
					String deviceString = connectPoint.deviceId().toString();
					String portString = connectPoint.port().toString();
					String macString = dstMac.toString();
					String msgString = "MAC " +macString+" ,is matched on of:"+deviceString+"! Install flow rule!";
					log.info(msgString);
					FlowRule flowRule = DefaultFlowRule.builder()
						.withSelector(DefaultTrafficSelector.builder().matchEthDst(dstMac).matchEthSrc(srcMac).build())
						.withTreatment(DefaultTrafficTreatment.builder().setOutput(outputPort).build())
						.forDevice(connectPoint.deviceId())
						.withPriority(20)
						.makeTemporary(20)
						.fromApp(appId).build();

					flowRuleService.applyFlowRules(flowRule);
				}
				packetContext.treatmentBuilder().setOutput(outputPort);
				packetContext.send();
			}
			//destination mac not in the table	
			else{
				String deviceString = connectPoint.deviceId().toString();
				String portString = connectPoint.port().toString();
				String macString = dstMac.toString();
				String msgString = "MAC " +macString+" ,is missed on of:"+deviceString+"! Flood packet!";
				log.info(msgString);
				floodFunc(packetContext);
			}


		}
	}
    @Override
    public void someMethod() {
        log.info("Invoked");
    }

}
