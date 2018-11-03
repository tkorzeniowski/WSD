package messages;
import jade.core.AID;

public class Offer {
    private AID aid;
    private String provider;
    private double demand, price;

    public Offer(AID aid, String provider, double demand, double price){
        this.aid = aid;
        this.provider = provider;
        this.demand = demand;
        this.price = price;
    }

    public AID getAid() {
        return aid;
    }

    public String getProvider(){
        return provider;
    }

    public double getDemand() {
        return demand;
    }

    public double getPrice() { return price; }

    /* public void setAid(AID aid) { this.aid = aid; } */

    /* public void setProvider(String provider) { this.provider = provider; } */

    public void setDemand(double demand) { this.demand = demand; }

    /* public void setPrice(double price) { this.price = price; } */
}
