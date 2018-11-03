package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import java.util.concurrent.ThreadLocalRandom;
import messages.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Battery extends Agent {

    private AID buildingId;
    private int totalCapacity;
    private final String regex = ";";
    private double currentCapacity, priceLB, priceUB;
    private BatteryState batteryState;
    private String buildingName;

    private Logger logger;

    @Override
    protected void setup() {

        logger = LoggerFactory.getLogger(getLocalName());
        logger.debug("started");

        Object[] args = getArguments();

        ServiceType st = (ServiceType) args[0]; // each battery provides 'battery' service registered in DF
        setService(st.toString());

        String[] parts = (String[]) args[1];

        //totalCapacity = ThreadLocalRandom.current().nextInt(100, 200);
        totalCapacity = Integer.parseInt(parts[2]); // for tests
        //currentCapacity = ThreadLocalRandom.current().nextDouble(0.4, 1.0); // percentage
        currentCapacity = 0.7; // for tests
        buildingName = parts[1]; // parts[0] - batteryId used to create agent

        /* */
        addBehaviour(new WakerBehaviour(this, 1000) {
            @Override
            protected void onWake() {
                findBuilding(buildingName);
            }
        });

        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if(msg != null){
                    if(msg.getOntology().equals(StatusType.BATTERY_CAPACITY.toString())){
                        predictCapacity();
                        informBuilding();
                        logger.info("informing " + msg.getSender().getLocalName() + " of state and capacity");
                    }
                    else if(msg.getOntology().equals(StatusType.CHARGE_BATTERY.toString())){

                        currentCapacity += Double.parseDouble(msg.getContent())/totalCapacity;
                        updateCapacityInfo();
                        logger.info("charging up - " + Double.parseDouble(msg.getContent())/totalCapacity);
                    }
                    else if(msg.getOntology().equals(StatusType.GET_PRICE.toString())){

                        sendPrice(msg.getSender());
                    }
                    else if(msg.getOntology().equals(StatusType.REQUEST_MEDIUM.toString())){

                        String[] parts = msg.getContent().split(regex);

                        currentCapacity -= Double.parseDouble(parts[0])/totalCapacity;
                        // parts[1] - price for medium determined in GET_PRICE negotiations, price might have changed since then but consumer accepted old price
                        ACLMessage message = new ACLMessage(ACLMessage.INFORM);
                        message.addReceiver(msg.getSender());
                        message.setOntology(StatusType.REQUEST_MEDIUM.toString());
                        message.setContent(parts[0]);
                        send(message);

                        updateCapacityInfo();

                    }
                    else{
                        logger.info("message from" + msg.getSender() + " not understood");
                        msg.setPerformative(ACLMessage.NOT_UNDERSTOOD);
                        msg.addReceiver(msg.getSender());
                        msg.setContent(msg.getContent());
                        // send(msg);
                    }
                } else block();
            }
        });


    }

    /** predict capacity for next period (assumption that battery capacity can change without other agents knowledge/actions)
     * */
    private void predictCapacity(){
        if(System.currentTimeMillis() % 2 == 0){
            currentCapacity += ThreadLocalRandom.current().nextDouble(0, 0.05);
        }else{
            currentCapacity -= ThreadLocalRandom.current().nextDouble(0, 0.05);
        }
    }

    /** update capacity info, i.e. set current battery state and price for medium
     * */
    private void updateCapacityInfo(){
        if(currentCapacity < 0.1){
            batteryState = BatteryState.REQUEST_MEDIUM;
            priceLB = 10000;
            priceUB = 10000;
        } else if(currentCapacity >= 0.1 && currentCapacity < 0.5){
            batteryState = BatteryState.STORE_MEDIUM;
            priceLB = 0.4;
            priceUB = 1;
        } else if (currentCapacity >= 0.5 && currentCapacity < 0.9){
            batteryState = BatteryState.SEND_MEDIUM;
            priceLB = 0.1;
            priceUB = 0.5;
        } else {
            batteryState = BatteryState.EXCESS_MEDIUM;
            priceLB = 0.01;
            priceUB = 0.1;
        }
    }

    /** inform about current price for medium
     * @param sender who to inform*/
    private void sendPrice(AID sender){
        updateCapacityInfo();
        ACLMessage message = new ACLMessage(ACLMessage.INFORM);
        message.addReceiver(sender);
        message.setOntology(StatusType.GET_PRICE.toString());
        message.setContent(String.valueOf(ThreadLocalRandom.current().nextDouble(priceLB, priceUB)));
        send(message);
    }

    /** send building current capacity and state
     * */
    private void informBuilding(){
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.addReceiver(buildingId);
        msg.setContent(String.valueOf(currentCapacity));
        msg.setOntology(StatusType.BATTERY_CAPACITY.toString());

        updateCapacityInfo();
        msg.setLanguage(batteryState.toString());

        send(msg);
    }

    /** register service in DF
     * @param type type of service to register */
    private void setService(String type){
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(type);
        sd.setName(getLocalName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException ex) {
            ex.printStackTrace();
        }
    }

    /** search directory facilitator to find building and inform it of new battery
     * @param buildingName building name obtained from initial battery arguments
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

            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setOntology(StatusType.DECLARE_BATTERY.toString());
            msg.setContent(String.valueOf(totalCapacity));
            msg.addReceiver(buildingId);
            send(msg);

        }
        catch (FIPAException ex) {
            ex.printStackTrace();
        }
    }

    /** actions before agent (sometimes unexpected) termination
     * */
    @Override
    protected void takeDown()
    {
        logger.warn("stopping");
        try { DFService.deregister(this); }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

}
