package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import messages.*;

public class Building extends Agent {

    private AID batteryId;
    private double predictedProduction, actualProduction, totalDemand, batteryCapacity, batteryTotalCapacity, excessiveProduction;
    private int consumersCount, offersCount, neighboursCount;
    private List<Offer> consumerOffers, buildingOffers, reservedMedium;
    private final String regex = ";";
    private List<String> estateIds, services;
    private BatteryState batteryState;
    private boolean negotiationsStarted = false, supplyPlanNotStarted = true;

    private Logger logger;


    @Override
    protected void setup() {

        logger = LoggerFactory.getLogger(getLocalName());
        logger.info("started");

        Object[] args = getArguments();
        ServiceType st = (ServiceType) args[0];
        String[] parts = (String[]) args[1]; // other properties

        predictedProduction = Double.parseDouble(parts[1]); // parts[0] - buildingId used to create agent
        actualProduction = predictedProduction; // just for initialization

        services = new LinkedList<>();
        services.add(st.toString());

        estateIds = new LinkedList<>();
        for(String s :(parts[2]).split("-")){
            services.add(s);
            estateIds.add(s);
        }

        registerServices(services);

        offersCount = 0;
        consumersCount = 0;

        consumerOffers = new LinkedList<>();
        buildingOffers = new LinkedList<>();
        reservedMedium = new LinkedList<>();


        /* reactions for incoming messages */
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if(msg != null){
                    if (msg.getOntology().equals(StatusType.CANCEL_CONSUMER.toString())){
                        logger.info("canceling consumer - " + msg.getSender().getLocalName());
                        --consumersCount;
                    }
                    else if(msg.getOntology().equals(StatusType.OFFER.toString())){
                        logger.info("received offer from " + msg.getSender().getLocalName() + " - " + msg.getContent());
                        saveOffer(msg.getSender(), msg.getContent());
                    }
                    else if(msg.getOntology().equals(StatusType.DECLARE_BATTERY.toString())){
                        batteryId = msg.getSender();
                        batteryTotalCapacity = Double.parseDouble(msg.getContent());
                        logger.info("registering battery - " + batteryId.getLocalName());
                    }
                    else if(msg.getOntology().equals(StatusType.BATTERY_CAPACITY.toString())){
                        batteryCapacity = Double.parseDouble(msg.getContent());
                        batteryState = BatteryState.valueOf(msg.getLanguage());
                        logger.info("current battery capacity - " + batteryCapacity + " (" + batteryState + ")");
                    }
                    else if(msg.getOntology().equals(StatusType.GET_BATTERY.toString())){
                        logger.info("accepting consumer - " + msg.getSender().getLocalName());
                        ++consumersCount;

                        ACLMessage message = new ACLMessage(ACLMessage.INFORM);
                        message.setOntology(StatusType.GET_BATTERY.toString());
                        if(batteryId != null) {
                            message.setContent(batteryId.getLocalName());
                        }
                        message.addReceiver(msg.getSender());
                        send(message);
                    }
                    else if(msg.getOntology().equals(StatusType.MEDIUM_NEEDED.toString())){
                        if(msg.getContent()!=null){
                            receiveMedium(msg);
                        } else {
                            reserveMedium(msg.getSender());
                        }
                    }
                    else{
                        logger.info("message from" + msg.getSender() + " not understood");
                        msg.setPerformative(ACLMessage.NOT_UNDERSTOOD);
                        msg.addReceiver(msg.getSender());
                        msg.setContent(msg.getContent());
                        send(msg);
                    }

                }else block();
            }
        });


        /* get parameters and create some agent state*/
        TickerBehaviour tbPrediction = new TickerBehaviour(this, 9000) {
            @Override
            protected void onTick() {
                predictProduction();
                getBatteryState();
                if(this.getTickCount() % 3 == 0){
                    logger.info("RESET");
                    this.reset();
                }
            }
        };
        addBehaviour(tbPrediction);

        /* check if all consumers sent offers, start creating supply plan regardless */
        TickerBehaviour tbSupplyPlan = new TickerBehaviour(this, 11000) {
            @Override
            protected void onTick() {
                if(supplyPlanNotStarted){
                    createSupplyPlan();
                }
                if(this.getTickCount() % 3 == 0){
                    logger.info("RESET");
                    this.reset();
                }
            }
        };
        addBehaviour(tbSupplyPlan);

        /* before the end of period send medium to consumers, give excessive production to battery,
        inform providers of maximum demand they can expect and clean agent state before next period */
        TickerBehaviour tbSendMedium = new TickerBehaviour(this, 13000) {
            @Override
            protected void onTick() {
                sendMedium();
                logger.info("medium sent to consumers");

                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.addReceiver(batteryId);
                msg.setContent(String.valueOf(excessiveProduction));
                msg.setOntology(StatusType.CHARGE_BATTERY.toString());
                send(msg);

                informProviders();
                cleanUp();

                if(this.getTickCount() % 3 == 0){
                    logger.info("RESET");
                    this.reset();
                }

            }
        };
        addBehaviour(tbSendMedium);

    }

    /** store each consumer offer and start preparing supply plan (if each registered consumer sent offer)
     * @param aid agent identifier
     * @param offer demand of consumer stored as offer*/
    private void saveOffer (AID aid, String offer){

        if(consumerOffers == null){
            consumerOffers = new LinkedList<>();
        }

        String[] parts = offer.split(regex);
        consumerOffers.add(new Offer(aid, parts[0], Double.parseDouble(parts[1]), 0.0));

        totalDemand += consumerOffers.get(consumerOffers.size()-1).getDemand();

        ++offersCount;

        if(offersCount == consumersCount){ // no need to wait for more offers
            createSupplyPlan();
        }

    }

    /** called each time agents try to create supply plan, main steps: starting negotiations if medium is needed,
     * storing some medium in battery depending on it's state, selecting offers of other buildings
     * */
    private void createSupplyPlan(){
        if(supplyPlanNotStarted){ // all consumers send their offers or reached timeout (preparing supply plan for consumers that sent offers)
            logger.info("preparing supply plan");
            supplyPlanNotStarted = false;
        }

        if(negotiationsStarted){
            logger.info("negotiations in progress");
            if(neighboursCount == 0){
                selectOffers();
            }

        } else {
            if(batteryId != null) { // battery needs some priority in medium production
                logger.info("checking battery state");
                checkBatteryState(); // based on actual production
            }

            //double excessiveProduction = getExcess();
            //logger.info("excessiveProduction: " + excessiveProduction);
            if(actualProduction < totalDemand && !negotiationsStarted){
                logger.info("starting negotiations");
                negotiationsStarted = true;
                startNegotiations();
            }
        }


    }

    /** if building produces energy, battery can be charged when it is in REQUEST_MEDIUM or STORE_MEDIUM state,
     * this sets priority that battery gets medium before consumers only in these two states
     * */
    private void checkBatteryState(){
        if(actualProduction > 0) {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.addReceiver(batteryId);
            msg.setOntology(StatusType.CHARGE_BATTERY.toString());

            if (batteryState.equals(BatteryState.REQUEST_MEDIUM)) {

                if (actualProduction > batteryTotalCapacity * 0.1) {
                    actualProduction -= batteryTotalCapacity * 0.1;
                    msg.setContent(String.valueOf(batteryTotalCapacity * 0.1));
                } else {
                    msg.setContent(String.valueOf(actualProduction));
                    actualProduction = 0;
                }
                send(msg);
            }
            else if (batteryState.equals(BatteryState.STORE_MEDIUM)) {
                double quantity = actualProduction * 0.05;
                actualProduction -= quantity;
                msg.setContent(String.valueOf(quantity));
                send(msg);
            }
        }
    }

    /** called when other building asks for medium, count excessive amount of medium that can be send and do it
     * @param sender building asking for medium*/
    private void reserveMedium(AID sender){
        double excessiveProd = getExcess();
        logger.info("reserving medium for " + sender.getLocalName() + " - " + excessiveProd);

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.addReceiver(sender);
        msg.setOntology(StatusType.MEDIUM_NEEDED.toString());

        if(reservedMedium == null){
            reservedMedium = new LinkedList<>();
        }

        double price = ThreadLocalRandom.current().nextDouble(0.01, 1);
        reservedMedium.add(new Offer(sender, null, excessiveProd, price));

        msg.setContent(String.valueOf(excessiveProd) + regex + false + regex + price); // mediumAmount;isThisMediumBeingReturned;price
        send(msg);
    }

    /** building receiving medium can get two king of messages: 1. other building offers medium  or 2. other building returns excess of medium
     * @param message message containing information of medium*/
    private void receiveMedium(ACLMessage message){
        String[] parts = message.getContent().split(regex);

        if(buildingOffers == null){
            buildingOffers = new LinkedList<>();
        }

        boolean isMediumReturned = Boolean.valueOf(parts[1]);

        if(!isMediumReturned){ // building got medium from another building

            double mediumQuantity = Double.parseDouble(parts[0]);
            if(mediumQuantity > 0){ // store only meaningful offers (also exclude offer from myself)
                logger.info(message.getSender().getLocalName() + " send medium - " + mediumQuantity + ", price - " + Double.parseDouble(parts[2]));
                buildingOffers.add(new Offer(message.getSender(), null, mediumQuantity, Double.parseDouble(parts[2]) ));
            }
            --neighboursCount;
        } else { // other building returned excessive amount of medium

            int i = 0;
            for(Offer rm : reservedMedium){
                if(rm.getAid().equals(message.getSender())){
                    rm.setDemand(rm.getDemand() - Double.parseDouble(parts[0]) );
                    logger.info(rm.getAid().getLocalName() + " reserved medium - " + rm.getDemand());
                    if(rm.getDemand() == 0 ){
                        reservedMedium.remove(i);
                    }
                    break;
                }
                ++i;
            }

            logger.info(message.getSender().getLocalName() + " returned medium - " + Double.parseDouble(parts[0]));
        }

        createSupplyPlan(); // check if supply plan can be created now

    }

    /** when all building neighbours send their offers, select the best ones
     * */
    private void selectOffers(){
        logger.info("selecting offers");

        if(buildingOffers != null) {
            buildingOffers.sort(Comparator.comparingDouble(Offer::getPrice)); // sort offers by price

            for (Offer o : buildingOffers) {

                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.addReceiver(o.getAid());
                msg.setOntology(StatusType.MEDIUM_NEEDED.toString());

                if (actualProduction + o.getDemand() <= totalDemand) { // still not enough medium
                    actualProduction += o.getDemand();
                    msg.setContent(String.valueOf(0) + regex + true + regex + 0);
                } else { // more medium than need, return some
                    double overproduction = actualProduction + o.getDemand() - totalDemand;
                    actualProduction += o.getDemand() - overproduction;
                    msg.setContent(String.valueOf(overproduction) + regex + true + regex + 0);
                }

                send(msg);
            }
        }
    }

    /** predict demand for next period
     * */
    private void predictProduction(){
        //predictedProduction = ThreadLocalRandom.current().nextDouble(actualProduction*0.95, actualProduction*1.05);  //firstTry
        //actualProduction = ThreadLocalRandom.current().nextDouble(predictedProduction*0.95, predictedProduction*1.05);
        actualProduction = predictedProduction;
    }

    /** send message to battery to get it's state
     * */
    private void getBatteryState(){
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology(StatusType.BATTERY_CAPACITY.toString());
        msg.addReceiver(batteryId);
        send(msg);
    }

    /** count amount of excessive production based on consumer offers and medium reserved for other buildings
     * @return amount of excessive production*/
    private double getExcess(){
        double consumerSupply = actualProduction/consumersCount;
        double excessiveSupply = 0;

        if(consumerOffers != null) {
            for (Offer co : consumerOffers) {
                if (consumerSupply >= co.getDemand()) { // consumer can get more than needs
                    excessiveSupply += consumerSupply - co.getDemand();
                }
            }
        }

        if(reservedMedium != null) {
            for (Offer rm : reservedMedium) {
                excessiveSupply -= rm.getDemand();
            }
        }

        return excessiveSupply;
    }

    /** send medium to each consumer and count excessive production
     * */
    private void sendMedium() {
        double consumerSupply = actualProduction / consumersCount;

        logger.info("sending medium to consumers");

        for (Offer co : consumerOffers) {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setOntology(StatusType.SUPPLY.toString());
            msg.addReceiver(co.getAid());

            if (consumerSupply >= co.getDemand()) { // consumer can get more than needs
                excessiveProduction += consumerSupply - co.getDemand();
                msg.setContent(String.valueOf(co.getDemand()));
                co.setDemand(0.0);
            } else { // consumer needs more than can get from building production
                co.setDemand(co.getDemand() - consumerSupply);
                msg.setContent(String.valueOf(consumerSupply));
            }

            send(msg);
        }
    }

    /** inform providers of maximum medium amount that consumers can request (maximum, because consumers might get shortages form battery)
     * */
    private void informProviders(){
        Map<String, Double> demandPerProvider = new HashMap<>();
        for(Offer co : consumerOffers) {
            if(demandPerProvider.containsKey(co.getProvider())){
                demandPerProvider.put(co.getProvider(), demandPerProvider.get(co.getProvider()).doubleValue() + co.getDemand());
            }else{
                demandPerProvider.put(co.getProvider(), co.getDemand());
            }
        }

        for (String name: demandPerProvider.keySet()){
            logger.info("sending demand to provider " + name + " " + demandPerProvider.get(name).toString());
        }

    }


    /** find all neighbours in DF and ask them for medium
     * */
    private void startNegotiations(){
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();

        for(String e : estateIds){
            sd.setType(e);
            template.addServices(sd);

            try {
                DFAgentDescription[] result = DFService.search(this, template);

                for(DFAgentDescription dfd : result){

                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.setOntology(StatusType.MEDIUM_NEEDED.toString());
                    msg.addReceiver(dfd.getName());
                    send(msg);

                    ++neighboursCount;
                }

            }
            catch (FIPAException ex) {
                ex.printStackTrace();
            }

        }

        if(batteryId != null) { // ask battery as well
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setOntology(StatusType.MEDIUM_NEEDED.toString());
            //msg.setContent(regex); // send anything
            msg.addReceiver(batteryId);
            send(msg);

            ++neighboursCount;
        }
    }

    /** register services that building provide
     * @param serviceType service type to register*/
    private void registerServices(List<String> serviceType) {

        DFAgentDescription dfd = new DFAgentDescription();   // Create the agent description
        dfd.setName(getAID());

        ServiceDescription sd;      // Add all the services to the agent description
        for(String st : serviceType){
            sd = new ServiceDescription();
            sd.setName(getLocalName());
            sd.setType(st);
            dfd.addServices(sd);
        }

        try {        // Register the dfd
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }

    /** clean agent state before next period
     * */
    private void cleanUp(){
        actualProduction = 0;
        totalDemand = 0;
        excessiveProduction = 0;
        offersCount = 0;
        neighboursCount = 0;
        consumerOffers.clear();
        buildingOffers.clear();
        reservedMedium.clear();
        negotiationsStarted = false;
        supplyPlanNotStarted = true;
    }

    /** actions before agent (sometimes unexpected) termination
     * */
    @Override
    protected void takeDown() {
        logger.warn("stopping");
        try { DFService.deregister(this); }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

}
