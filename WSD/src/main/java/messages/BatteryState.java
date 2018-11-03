package messages;

/** states of battery that determine it's behaviour
 * */
public enum BatteryState {
    /** battery needs to be charged */
    REQUEST_MEDIUM,
    /** battery will gladly accept medium */
    STORE_MEDIUM,
    /** battery will send medium where it is needed */
    SEND_MEDIUM,
    /** battery is almost full and will accept only excesses */
    EXCESS_MEDIUM,
}