package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import java.io.*;
import java.util.concurrent.ThreadLocalRandom;
import messages.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Consumer extends Agent {

    private String providerId, buildingName;
    private final String regex = ";";
    private AID buildingId, batteryId;
    private double initialDemand, actualDemand, predictedDemand, providerPrice;

    private Logger logger;

    @Override
    protected void setup() {

        logger = LoggerFactory.getLogger(getLocalName());
        logger.info("started");

        /* get consumer's arguments - consumer name, building name, provider, demand */
        Object[] args = getArguments();
        String[] parts = (String[]) args[1]; // args[0] - ServiceType.CONSUMER (not used)

        buildingName = parts[1];     // parts[0] - consumerId used to create agent
        providerId = parts[2];      // name of provider

        initialDemand = Double.parseDouble(parts[3]);
        predictedDemand = 0;    // initialization
        actualDemand = 0;

        providerPrice = ThreadLocalRandom.current().nextDouble(0.01, 1);

        /* find building and battery after starting */
        addBehaviour(new WakerBehaviour(this, 2000) {
            @Override
            protected void onWake() {
                findBuilding(buildingName);
                findBattery();
            }
        });

        /* inform building of predicted demand for next period */
        addBehaviour(new TickerBehaviour(this, 10000) {
            @Override
            protected void onTick() {
                predictDemand();
                actualDemand += predictedDemand;

                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.setContent(providerId + regex + actualDemand);
                msg.setOntology(StatusType.OFFER.toString());
                msg.addReceiver(buildingId);
                send(msg);
            }
        });

        /* this agent behaviour determines it's actions concerning incoming messages */
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if(msg != null){
                    if(msg.getOntology().equals(StatusType.UPDATE_PROVIDER.toString())){
                        providerId = msg.getContent();
                        logger.info("changing provider to - " + providerId);
                        updateProvider();
                    }
                    else if (msg.getOntology().equals(StatusType.SUPPLY.toString())){
                        actualDemand -= Double.parseDouble(msg.getContent());
                        logger.info("received supply, current demand - " + actualDemand);

                        if(actualDemand > 0){
                            ACLMessage message = new ACLMessage(ACLMessage.INFORM);
                            message.addReceiver(batteryId);
                            message.setOntology(StatusType.GET_PRICE.toString());
                            send(message);
                        }else {
                            informProvider();
                        }
                    }
                    else if (msg.getOntology().equals(StatusType.GET_BATTERY.toString())){
                        setBatteryId(msg);
                    }
                    else if (msg.getOntology().equals(StatusType.GET_PRICE.toString())){
                        getShortage(Double.parseDouble(msg.getContent()) );
                    }
                    else if (msg.getOntology().equals(StatusType.REQUEST_MEDIUM.toString())){
                        actualDemand -= Double.parseDouble(msg.getContent());
                        informProvider();
                    }
                    else if(msg.getOntology().equals(StatusType.CONSUMER_CHARGING.toString())){
                        logger.info("need more medium for car charging - " + Double.parseDouble(msg.getContent()));
                        actualDemand += Double.parseDouble(msg.getContent());
                        logger.info("actual demand - " + actualDemand);
                    }
                    else{
                        logger.info("message from" + msg.getSender() + " not understood");
                        msg.setPerformative(ACLMessage.NOT_UNDERSTOOD);
                        msg.addReceiver(msg.getSender());
                        msg.setContent(msg.getContent());
                        //send(msg);
                    }
                } else block();
            }
        });

    }

    /** get buildingId (AID) based on service and building name
     * @param buildingName building name obtained from initial agent's arguments
     * */
    private void findBuilding(String buildingName){
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(ServiceType.BUILDING.toString()); // find all agents that provide 'building' service
        sd.setName(buildingName);   // one of those agents is my building
        template.addServices(sd);

        try {
            DFAgentDescription[] result = DFService.search(this, template);
            for(DFAgentDescription dfd : result){
                buildingId = dfd.getName();
            }
        }
        catch (FIPAException ex) {
            ex.printStackTrace();
        }
    }

    /** ask building about battery identifier
     * */
    private void findBattery(){
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology(StatusType.GET_BATTERY.toString());
        msg.addReceiver(buildingId);
        send(msg);
    }

    /** set battery identifier for later use (e.g. getting shortage of medium from battery instead of provider)
     * @param msg message received from building containing battery name or null if building doesn't have a battery
     * */
    private void setBatteryId(ACLMessage msg){
        if(msg.getContent() != null) {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(ServiceType.BATTERY.toString()); // find all agents that provide 'battery' service
            sd.setName(msg.getContent());   // one of those agents is my battery
            template.addServices(sd);

            try {
                DFAgentDescription[] result = DFService.search(this, template);

                for(DFAgentDescription dfd : result){
                    batteryId = dfd.getName();
                }

            } catch (FIPAException ex) {
                ex.printStackTrace();
            }
            logger.info("my battery " + batteryId.getLocalName());
        }
    }

    /** get additional medium from battery or provider depending on offered price
     * @param batteryPrice price of medium offered by battery
     * */
    private void getShortage(double batteryPrice){
        logger.info("battery price = " + batteryPrice + ", provider price " + providerPrice);
        if(batteryPrice <= providerPrice){
            logger.info("getting shortage from battery");
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.addReceiver(batteryId);
            msg.setOntology(StatusType.REQUEST_MEDIUM.toString());
            msg.setContent(String.valueOf(actualDemand+regex+batteryPrice));
            send(msg);
        } else {
            logger.info("getting shortage from provider");
            informProvider();
        }
    }

    /** predict demand for next period
     * */
    private void predictDemand(){
        //predictedDemand = ThreadLocalRandom.current().nextDouble(initialDemand*0.95, initialDemand*1.05);
        predictedDemand = initialDemand; // for tests
    }

    private void informProvider(){
        logger.info("need " + actualDemand + " medium from " + providerId);
        //actualDemand = 0;
    }

    /** when provider changes, building needs to be informed
     * */
    private void updateProvider(){
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology(StatusType.UPDATE_PROVIDER.toString());
        msg.addReceiver(buildingId);
        msg.setContent(providerId);
        send(msg);
    }

    /*
    // save agent state (snapshot)
    private void saveAgentState(){
        ClassLoader classLoader = getClass().getClassLoader();
        String path  = classLoader.getResource("").getPath() + getLocalName();

        try {
            FileOutputStream fout = new FileOutputStream(path);
            ObjectOutputStream oos = new ObjectOutputStream(fout);
            oos.writeObject(this);

            logger.info("state saved");

        } catch (Exception ex) {

            ex.printStackTrace();
        }
    }

    // restore agent state (last saved snapshot if exists)
    private void restoreAgent(){
        ClassLoader classLoader = getClass().getClassLoader();
        String path  = classLoader.getResource("").getPath() + getLocalName();

        File file = new File(path);
        if(file.exists() && !file.isDirectory()) {
            try{
                FileInputStream fis = new FileInputStream(file);
                this.restore(fis);
            } catch (IOException e){
                e.printStackTrace();
            }
        }
    }
    */

    /** actions before agent (sometimes unexpected) termination
     * */
    @Override
    protected void takeDown() {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology(StatusType.CANCEL_CONSUMER.toString());
        msg.addReceiver(buildingId);
        send(msg);
        logger.warn("stopping");
    }

}
