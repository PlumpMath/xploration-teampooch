package es.upm.company03;

import es.upm.common03.LocationUtility;
import es.upm.common03.TeamAgent;
import es.upm.common03.ontology.InformAID;
import es.upm.common03.pathFinding.LocationFactory;
import es.upm.common03.pathFinding.LocationNode;
import es.upm.common03.pathFinding.Map;
import es.upm.company03.behaviors.Rover.*;
import es.upm.ontology.*;
import jade.content.lang.Codec;
import jade.content.onto.OntologyException;
import jade.content.onto.basic.Action;
import jade.core.AID;
import jade.core.behaviours.FSMBehaviour;
import jade.lang.acl.ACLMessage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.logging.Level;

/**
 * Created by borismakogonyuk on 30.04.16.
 */
public class Rover extends TeamAgent {
    public Location getCapsuleLocation() {
        return capsuleLocation;
    }

    public enum RoverJobs{
        STARTING,
        ROAMING,
        DELIVERING
    }
    private static class FSMStates {
        public static final String START = "START";
        public static final String ROAMING = "ROAMING";
        public static final String DELIVERING = "DELIVERING";
        public static final String MOVING = "MOVING";
        public static final String ANALYZING = "ANALYZING";
        public static final String SYNCING = "SYNCING";
    }
    FSMBehaviour fsm;
    private Location myLocation, capsuleLocation;
    private int nextDirection;
    private AID worldAID, brokerAID;
    private HandleMovement bhvMovement;
    private HandleAnalysis bhvAnalysis;
    private int mapSizeX;
    private int mapSizeY;
    private Map<LocationNode> pathMap;

    public Map<LocationNode> getPathMap() {
        return pathMap;
    }

    private Findings totalFindings = new Findings();
    private Findings newFindings = new Findings();
    private HashMap<AID, Location> otherRovers = new HashMap<>();
    private RoverJobs currentJob = RoverJobs.STARTING;
    private boolean pathInvalidated = false;
    public void setPathInvalidated(boolean state){
        pathInvalidated = state;
    }
    public boolean isPathInvalidated(){
        return pathInvalidated;
    }
    public void updateRoverInfo(AID aid, Location location){
        pathInvalidated = true;
        otherRovers.put(aid, location);
    }
    public void addFinding(Location roverLocation, Mineral mineral) {
        Iterator<Finding> iter = totalFindings.getAllFinding();
        while(iter.hasNext()) {
            Finding find = iter.next();
            if (LocationUtility.areColliding(find.getLocation(), roverLocation))
                return;
        }
        Finding finding = new Finding();
        finding.setLocation(roverLocation);
        finding.setMineral(mineral);
        totalFindings.addFinding(finding);
        newFindings.addFinding(finding);
    }
    public int getFindingsCount(){ return newFindings.getFinding().size();}
    public RoverJobs getCurrentJob() {
        return currentJob;
    }
    public void setCurrentJob(RoverJobs currentJob) {
        this.currentJob = currentJob;
    }
    public int getNextDirection() {return nextDirection;}
    public void setNextDirection(int value) { this.nextDirection = value; }
    public Location getRoverLocation() {
        return myLocation;
    }
    public void setRoverLocation(Location myLocation) {
        this.myLocation = myLocation;
    }
    public int getMapSizeX() {
        return mapSizeX;
    }
    public int getMapSizeY() {
        return mapSizeY;
    }

    @Override
    protected void setup() {
        AID companyAID = null;
        Object[] arguments = getArguments();
        if (arguments.length >= 4 && arguments[0] != null) {
            companyAID = arguments[0] instanceof AID ? (AID) arguments[0] : null;
            myLocation = arguments[1] instanceof Location ? (Location) arguments[1] : null;
            capsuleLocation = arguments[1] instanceof Location ? (Location) arguments[1] : null;
            mapSizeX = arguments[2] instanceof Integer ? (Integer) arguments[2] : null;
            mapSizeY = arguments[3] instanceof Integer ? (Integer) arguments[3] : null;
        }
        if (myLocation == null) {
            logger.log(Level.SEVERE, "Tried to instantiate Rover without location.");
            return;
        }
        System.out.printf("%s: dropped at %d,%d%n", getLocalName(), myLocation.getX(), myLocation.getY());
        super.setup();
        worldAID = findService("World");
        brokerAID = findService("Broker");
        if(brokerAID == null){
            System.out.println("No Broker found. Lets hope they at least have a world :S ");
            brokerAID = worldAID;
        }

        if (worldAID == null || brokerAID == null) {
            System.out.println("CRITICAL. World or Broker missing");
            this.doDelete();
            return;
        }
        super.setup();
        addBehaviour(new HandleRoverInfo(this));
        pathMap = new Map<>(getMapSizeX(), getMapSizeY(), new LocationFactory());
        informCompany(companyAID);
        setupFSM();
    }

    private void setupFSM() {
        fsm = new FSMBehaviour();

        fsm.registerFirstState(new HandleInitialCapsuleHandshake(this, brokerAID), FSMStates.START);
        fsm.registerState(new HandleRoaming(this, otherRovers), FSMStates.ROAMING);
        fsm.registerState(new HandleDelivering(this), FSMStates.DELIVERING);
        fsm.registerState(new HandleMovement(this, worldAID), FSMStates.MOVING);
        fsm.registerState(new HandleAnalysis(this, worldAID), FSMStates.ANALYZING);
        fsm.registerDefaultTransition(FSMStates.START, FSMStates.ROAMING);
        fsm.registerTransition(FSMStates.ROAMING, FSMStates.ROAMING, 0);
        fsm.registerTransition(FSMStates.ROAMING, FSMStates.DELIVERING, HandleRoaming.EndCodes.TO_DELIVERING, new String[]{FSMStates.DELIVERING});
        fsm.registerTransition(FSMStates.ROAMING, FSMStates.MOVING, HandleRoaming.EndCodes.TO_MOVING, new String[]{FSMStates.MOVING});
        fsm.registerTransition(FSMStates.ROAMING, FSMStates.ANALYZING, HandleRoaming.EndCodes.TO_ANALYZING, new String[]{FSMStates.ANALYZING});
        fsm.registerTransition(FSMStates.DELIVERING, FSMStates.DELIVERING, 0);
        fsm.registerTransition(FSMStates.DELIVERING, FSMStates.ROAMING, HandleDelivering.EndCodes.TO_ROAMING);
        fsm.registerTransition(FSMStates.DELIVERING, FSMStates.MOVING, HandleDelivering.EndCodes.TO_MOVING, new String[]{FSMStates.MOVING});
        fsm.registerTransition(FSMStates.MOVING, FSMStates.ROAMING, HandleMovement.EndCodes.TO_ROAMING);
        fsm.registerTransition(FSMStates.MOVING, FSMStates.DELIVERING, HandleMovement.EndCodes.TO_DELIVERING);
        fsm.registerTransition(FSMStates.ANALYZING, FSMStates.ROAMING, 0);
        addBehaviour(fsm);
    }

    public boolean isLocationAnalyzed(Location location){
        Iterator<Finding> iter = totalFindings.getAllFinding();
        while(iter.hasNext()){
            Finding find = iter.next();
            if(LocationUtility.areColliding(find.getLocation(),location))
                return true;
        }
        return false;
    }
    public void informMovement() {
        try {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setOntology(getxOntology().getName());
            msg.setLanguage(getCodec().getName());
            msg.setProtocol(getxOntology().PROTOCOL_MOVE_INFO);
            msg.addReceiver(brokerAID);
            MoveInformation moveInfo = new MoveInformation();
            es.upm.ontology.Rover rover = new es.upm.ontology.Rover();
            rover.setName("Rover03");
            rover.setRover_agent(getAID());
            moveInfo.setRover(rover);
            moveInfo.setLocation(myLocation);
            Direction direction = new Direction();
            direction.setX(nextDirection);
            moveInfo.setDirection(direction);
            getContentManager().fillContent(msg, new Action(getAID(), moveInfo));
            send(msg);
        } catch (Codec.CodecException e) {
            e.printStackTrace();
        } catch (OntologyException e) {
            e.printStackTrace();
        }
    }
    public void informFindings() {
        try {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setOntology(getxOntology().getName());
            msg.setLanguage(getCodec().getName());
            msg.setProtocol(getxOntology().PROTOCOL_UPDATE_FINDINGS);
            msg.addReceiver(brokerAID);
            FindingsMessage findingsMessage = new FindingsMessage();
            Frequency freq = new Frequency();
            freq.setChannel(3);
            findingsMessage.setFrequency(freq);
            findingsMessage.setFindings(newFindings);
            getContentManager().fillContent(msg, new Action(getAID(), findingsMessage));
            send(msg);
            newFindings.clearAllFinding();
        } catch (Codec.CodecException e) {
            e.printStackTrace();
        } catch (OntologyException e) {
            e.printStackTrace();
        }
    }
    private void informCompany(AID company) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setLanguage(codec.getName());
        msg.setOntology(teamOntology.getName());
        msg.setProtocol(teamOntology.PROTOCOL_INFORM_AID);
        msg.addReceiver(company);
        InformAID informAID = new InformAID();
        es.upm.ontology.Rover rover = new es.upm.ontology.Rover();
        rover.setName("Name");
        rover.setRover_agent(getAID());
        informAID.setSubject(rover);
        try {
            getContentManager().fillContent(msg, new Action(getAID(), informAID));
        } catch (Codec.CodecException e) {
            e.printStackTrace();
        } catch (OntologyException e) {
            e.printStackTrace();
        }
        send(msg);
    }
}

