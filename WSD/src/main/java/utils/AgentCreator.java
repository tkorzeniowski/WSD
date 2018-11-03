package utils;

import jade.core.behaviours.OneShotBehaviour;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;
import jade.core.Agent;

import java.io.*;
import java.util.Scanner;
import messages.ServiceType;

public class AgentCreator extends Agent {
    private ContainerController cc;
    private AgentController ac;

    private ClassLoader classLoader = AgentCreator.class.getClassLoader();

    private final static String regex = ";";

    @Override
    protected void setup() {
        addBehaviour(new OneShotBehaviour() {
            @Override
            public void action() {
                createAgents("buildings.txt", "agents.Building", ServiceType.BUILDING);
                createAgents("batteries.txt", "agents.Battery", ServiceType.BATTERY);
                createAgents("consumers.txt", "agents.Consumer", ServiceType.CONSUMER);
            }
        });
    }

    /** create all agents with specific parameters obtained from 'databases'
     * @param fileName file that contains agent arguments
     * @param className class implementing agent
     * @param serviceType service that agent provides
     * */
    private void createAgents(String fileName, String className, ServiceType serviceType){
        cc = getContainerController();

        File clientsFile = new File(classLoader.getResource(fileName).getFile());

        try (Scanner scanner = new Scanner(clientsFile)) {
            while (scanner.hasNextLine()) { // each line represents single agent
                String line = scanner.nextLine();
                String[] parts = line.split(regex); // initial parameters of each agent

                ac = cc.createNewAgent(parts[0], className, new Object[]{serviceType, parts}); // agent's name, class implementing agent, agent parameters
                ac.start();
            }

        } catch (jade.wrapper.StaleProxyException spe){
            spe.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
