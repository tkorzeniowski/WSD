package messages;
public enum ServiceType {
    BUILDING, // used to search for all buildings in directory facilitator
    BATTERY, // used to search for all batteries in directory facilitator
    CONSUMER, // not used (only to createAgents in AgentCreator to avoid unnecessary if statement)
}