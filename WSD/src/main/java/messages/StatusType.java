package messages;

/** statuses used to determine what kind of message agent receives, passed as ontology
 * */
public enum StatusType {
    /** change consumer's providerId */
    UPDATE_PROVIDER,
    /** inform building that consumer exists */
    DECLARE_CONSUMER,
    /** if consumer fails, informs building of it */
    CANCEL_CONSUMER,
    /** consumer sends demand to building as an offer */
    OFFER,
    /** battery connects to building */
    DECLARE_BATTERY,
    /** building asks battery for it's current capacity */
    BATTERY_CAPACITY,
    /** customer asks building for it's battery */
    GET_BATTERY,
    /** building sends medium to battery to charge it */
    CHARGE_BATTERY,
    /** building sends medium to consumer, consumer receives it and acts accordingly */
    SUPPLY,
    /** main protocol for negotiations between buildings to get medium shortages */
    MEDIUM_NEEDED,
    /** consumer asks battery for medium price */
    GET_PRICE,
    /** consumer requests medium from battery */
    REQUEST_MEDIUM,
}