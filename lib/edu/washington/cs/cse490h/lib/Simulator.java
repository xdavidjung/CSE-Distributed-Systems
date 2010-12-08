package edu.washington.cs.cse490h.lib;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;

import edu.washington.cs.cse490h.lib.Node.NodeCrashException;


/**
 * Manages a simulation.
 */
public class Simulator extends Manager {

	public static final int MAX_NODES_TO_SIMULATE = Packet.MAX_ADDRESS - 1;
    
	private HashMap<Integer, Node> nodes;
	private HashSet<Integer> crashedNodes;
	private ArrayList<Packet> inTransitMsgs;
	
	private HashSet<Timeout> canceledTimeouts;

	/**
	 * Base constructor for the Simulator. Does most of the work, but the
	 * command input method and failure level should be set before calling this
	 * constructor Should be called after the failure generator and
	 * 
	 * @param nodeImpl
	 *            The Class object for the student's node implementation
	 * @param numNodes
	 *            How many nodes to simulate
	 * @throws IllegalArgumentException
	 *             If the arguments provided to the program are invalid
	 */
	public Simulator(Class<? extends Node> nodeImpl, Long seed) throws IllegalArgumentException {
		super(nodeImpl);
		
		setParser(new SimulationCommandsParser());
		
		setTime(0);
		
		if(userControl != FailureLvl.EVERYTHING){
			if(seed == null){
				this.seed = System.currentTimeMillis();
			}else{
				this.seed = seed;
			}
			System.out.println("Starting simulation with seed: " + this.seed);
			randNumGen = new Random(this.seed);
		}
		
		waitingTOs = new ArrayList<Timeout>();
		inTransitMsgs = new ArrayList<Packet>();
		nodes = new HashMap<Integer, Node>();
		crashedNodes = new HashSet<Integer>();
		Node.setNumNodes(0);
	}
	
	/**
	 * Constructor for a simulator that takes commands from a file.
	 * @param nodeImpl		The Class object for the student's node implementation
	 * @param numNodes		The number of nodes to simulate
	 * @param commandfile	File containing the list of commands
	 * @param failureGen	How failures should be generated
	 * @param seed			Seed for the RNG.
	 * 						This can be null if the failure generator is not a RNG
	 * @throws IllegalArgumentException
	 * @throws FileNotFoundException
	 */
	public Simulator(Class<? extends Node> nodeImpl, FailureLvl failureGen, String commandFile, Long seed) throws IllegalArgumentException, FileNotFoundException {
		this(nodeImpl, seed);

		cmdInputType = InputType.FILE;
		userControl = failureGen;
		
		SimulationCommandsParser commandFileParser = new SimulationCommandsParser();
		sortedEvents = commandFileParser.parseFile(commandFile);
	}

	/**
	 * Constructor for a simulator that takes commands from the user.
	 * @param nodeImpl		The Class object for the student's node implementation
	 * @param numNodes		The number of nodes to simulate
	 * @param failureGen	How failures should be generated
	 * @param seed			Seed for the RNG.
	 * 						This can be null if the failure generator is not a RNG
	 * @throws IllegalArgumentException
	 */
	public Simulator(Class<? extends Node> nodeImpl, FailureLvl failureGen, Long seed) throws IllegalArgumentException {
		this(nodeImpl, seed);
		
		cmdInputType = InputType.USER;
		userControl = failureGen;
	}
	
	@Override
	public void start() {
		if(cmdInputType == InputType.FILE) {
			while(!inTransitMsgs.isEmpty() || !sortedEvents.isEmpty() || !waitingTOs.isEmpty()) {
				System.out.println("\nTime: " + now());
				
				ArrayList<Event> currentRoundEvents = new ArrayList<Event>();
				canceledTimeouts = new HashSet<Timeout>();
				
				// If a message was delivered successfully and therefore changed the state,
				// in transit messages should be checked again.
				//		Ex: In order to deliver a sequence of messages in reverse order, we need to
				//		recursively delay everything except the last message.
				checkInTransit(currentRoundEvents);
				
				boolean advance = false;
				do{
					if(sortedEvents.isEmpty()){
						advance = true;
					}else{
						Event ev = sortedEvents.remove(0);
						if(ev.t == Event.EventType.TIME) {
							advance = true;
						} else {
							currentRoundEvents.add(ev);
						}
					}
				}while(!advance);
				
				checkCrash(currentRoundEvents);
				
				checkTimeouts(currentRoundEvents);
				
				executeEvents(currentRoundEvents);
				
				setTime(now()+1);
			}
		}else if(cmdInputType == InputType.USER) {
			while(true){
				System.out.println("\nTime: " + now());
				
				ArrayList<Event> currentRoundEvents = new ArrayList<Event>();
				canceledTimeouts = new HashSet<Timeout>();
				
				Event ev;
				boolean advance = false;
				System.out.println("Please input a sequence of commands terminated by a blank line or the TIME command:");
				
				do{
					// just in case an exception is thrown or input is null
					ev = null;
					
					try{
						// A command will be converted into an Event and passed to the node later in the loop
						// A quit command will be matched later in this try block
						// Empty/whitespace will be treated as a skipped line, which will return null and cause a continue
						String input = keyboard.readLine();
						// Process user input if there is any
						if(input != null) {
							ev = parser.parseLine(input);
						}
					}catch(IOException e){
						System.err.println("Error on user input: " + e);
					}
					
					if(ev == null){
						advance = true;
					}else{
						if(ev.t == Event.EventType.TIME) {
							advance = true;
						} else {
							currentRoundEvents.add(ev);
						}
					}
				}while(!advance);
				
				checkCrash(currentRoundEvents);
				
				// See above for a discussion on why this needs to happen
				// The only difference is that, when a user is the failure generator,
				//		they can simply input a null command to get back to the message
				//		handling phase
				checkInTransit(currentRoundEvents);
				
				checkTimeouts(currentRoundEvents);
				
				setTime(now()+1);
			}
		}
		
		stop();
	}
	
	@Override
	public void stop(){
		System.out.println(stopString());
		for(Integer i: nodes.keySet()){
			System.out.println(i + ": " + nodes.get(i).toString());
		}
		for(Integer i: crashedNodes){
			System.out.println(i + ": failed");
		}
		System.exit(0);
	}

	/**
	 * Put the packet on the channel. Crashes in the middle of a broadcast can
	 * be modeled by a post-send crash, plus a sequence of dropped messages
	 * 
	 * @param from
	 *            The node that is sending the packet
	 * @param to
	 *            Integer specifying the destination node
	 * @param pkt
	 *            The packet to be sent, serialized to a byte array
	 * @throws IllegalArgumentException
	 *             If the send is invalid
	 */
	public void sendPkt(int from, int to, byte[] pkt) throws IllegalArgumentException {
		super.sendPkt(from, to, pkt);  // check arguments
		
		if(!isNodeValid(from)) {
			return;
		}
		
		Packet p = Packet.unpack(pkt);
		if(p == null){
			System.err.println("Bad packet: " + p);
			return;
		}
		
		if(to == Packet.BROADCAST_ADDRESS) {
			for(Integer i: nodes.keySet()) {
				if(i != from){
					inTransitMsgs.add(new Packet(i, from, p.getProtocol(), p.getPayload()));
				}
			}
		}else{
			inTransitMsgs.add(p);
		}
	}

	/**
	 * Sends command to the the specified node
	 * 
	 * @param nodeAddr
	 *            Address of the node to whom the message should be sent
	 * @param msg
	 *            The msg to send to the node
	 * @return True if msg sent, false if not
	 */
	public boolean sendNodeCmd(int nodeAddr, String msg) {
		if(!isNodeValid(nodeAddr)) {
			return false;
		}
		
		nodes.get(nodeAddr).onCommand(msg);
		return true;
	}

	/*************************** Protected Functions ***************************/
	
	@Override
	protected void checkWriteCrash(Node n, String description) {
		if(userControl.compareTo(FailureLvl.CRASH) < 0){
			if(randNumGen.nextDouble() < failureRate) {
				System.out.println("Randomly failing before write: " + n.addr);
				NodeCrashException e = failNode(n.addr);
				// This function is called by Node, so we need to rethrow the
				// exception to fully stop execution
				throw e;
			}
		}else{
			try{
				System.out.println("Crash node " + n.addr + " before " + description + "? (y/n)");
				String input = keyboard.readLine().trim();
				if(input.length() != 0 && input.charAt(0) == 'y'){
					NodeCrashException e = failNode(n.addr);
					// This function is called by Node, so we need to rethrow
					// the exception to fully stop execution
					throw e;
				}
			}catch(IOException e){
				e.printStackTrace();
			}
		}
	}

	/**
	 * Fail a node. This method updates data structures, removes the failed
	 * nodes's timeouts and calls its stop() method
	 * 
	 * @param node
	 *            The node address to fail
	 * @return The exception thrown after calling the stop() method. This is so,
	 *         if the stack includes methods in Node, we can rethrow the
	 *         Exception as necessary
	 */
	protected NodeCrashException failNode(int node){
		NodeCrashException crash = null;
		
		if(isNodeValid(node)) {
			Node crashingNode = nodes.get(node);
			try{
				crashingNode.stop();
			}catch(NodeCrashException e) {
				crash = e;
			}
			
			nodes.remove(node);
			crashedNodes.add(node);
			
			Iterator<Timeout> iter = waitingTOs.iterator();
			while(iter.hasNext()){
				Timeout to = iter.next();
				if(to.addr == node){
					canceledTimeouts.add(to);
				}
			}
		}
		
		return crash;
	}

	/**
	 * Start up a node, crashed or brand new. If the node is alive, this method
	 * will crash it first.
	 * 
	 * @param node
	 *            The address at which to start the node
	 */
	protected void startNode(int node){
		if(!validNodeAddress(node)){
			System.err.println("Invalid new node address: " + node);
			return;
		}
		
		if(nodes.containsKey(node)){
			failNode(node);
		}
		
		Node newNode;
		try{
			newNode = nodeImpl.newInstance();
		}catch(Exception e){
			System.err.println("Error while contructing node: " + e);
			failNode(node);
			return;
		}
		
		if(crashedNodes.contains(node)){
			crashedNodes.remove(node);
		}else{
			Node.setNumNodes(Node.getNumNodes()+1);
		}
		nodes.put(node, newNode);
		
		newNode.init(this, node);
		try{
			newNode.start();
		}catch(NodeCrashException e) { }
	}
	
	/**
	 * Check if the address is valid
	 * 
	 * @param addr
	 *            The address to check
	 * @return true if the address is valid, false otherwise
	 */
	protected static boolean validNodeAddress(int addr) {
		return (addr <= MAX_NODES_TO_SIMULATE && addr >= 0);
	}
	
	/*************************** Private Functions ***************************/

	/**
	 * Goes through all of the in transit messages and decides whether to drop,
	 * delay, or deliver. Also reorders delivered messages.
	 */
	private void checkInTransit(ArrayList<Event> currentRoundEvents) {
		if(inTransitMsgs.isEmpty()){
			return;
		}
		
		// See what we should do with all the in-transit messages
		ArrayList<Packet> currentPackets = inTransitMsgs;
		inTransitMsgs = new ArrayList<Packet>();
		
		if(userControl.compareTo(FailureLvl.DROP) < 0){		// userControl < DROP
			// Figure out if we need to drop the packet.
			Iterator<Packet> iter = currentPackets.iterator();
			while(iter.hasNext()) {
				Packet p = iter.next();
				double rand = randNumGen.nextDouble();
				if(rand < dropRate){
					System.out.println("Randomly dropping: " + p.toString());
					iter.remove();
				}
			}
		}else{
			System.out.println("The following messages are in transit: ");
			for(int i = 0; i < currentPackets.size(); ++i){
				System.out.println(i + ": " + currentPackets.get(i).toString());
			}

			try{
				System.out.println("Which should be dropped? (space delimited list or just press enter to drop none)");
				String input = keyboard.readLine().trim();
				// hash set so we don't have to deal with duplicates
				HashSet<Packet> toBeRemoved = new HashSet<Packet>();
				
				if(!input.equals("")){
					String[] dropList = input.split("\\s+");
					for(String s: dropList){
						toBeRemoved.add( currentPackets.get(Integer.parseInt(s)) );
					}
				}
				
				if(toBeRemoved.size() == currentPackets.size()){
					currentPackets.clear();
					return;
				}
				
				// If user drops and delays the same packet, result is undefined
				//   In current implementation, delay takes precedence
				if(userControl.compareTo(FailureLvl.DELAY) >= 0){		// userControl >= DELAY
					System.out.println("Which should be delayed? (space delimited list or just press enter to delay none)");
					input = keyboard.readLine().trim();
					
					if(!input.equals("")){
						String[] delayList = input.split("\\s+");
						for(String s: delayList){
							Packet p = currentPackets.get(Integer.parseInt(s));
							inTransitMsgs.add(p);
							toBeRemoved.add(p);
						}
					}
					
					if(toBeRemoved.size() == currentPackets.size()){
						return;
					}
				}
				
				currentPackets.removeAll(toBeRemoved);
			}catch(IOException e){
				e.printStackTrace(System.err);
			}
		}
		
		if(userControl.compareTo(FailureLvl.DELAY) < 0){		// userControl < DELAY
			Iterator<Packet> iter = currentPackets.iterator();
			while(iter.hasNext()) {
				Packet p = iter.next();
				double rand = randNumGen.nextDouble();
				// adjust the probability since these are not independent events
				//   Ex: 50% drop rate and 50% delay rate should mean that nothing gets through
				double adjustedDelay = delayRate / (1 - dropRate);
				if(rand < adjustedDelay){
					System.out.println("Randomly Delaying: " + p.toString());
					iter.remove();
					inTransitMsgs.add(p);
				}
			}
		}
		
		for(Packet p: currentPackets) {
			currentRoundEvents.add(new Event(p));
		}
	}
	
	/**
	 * Checks whether to crash any live node or restart any failed node
	 */
	private void checkCrash(ArrayList<Event> currentRoundEvents){
		// Failures specified in the file are deprecated
		if(userControl.compareTo(FailureLvl.CRASH) < 0){		// userControl < CRASH
			// make a copy so we don't have concurrent modification exceptions
			Integer[] addrCopy = nodes.keySet().toArray(new Integer[0]);
			
			for(Integer i: addrCopy){
				double rand = randNumGen.nextDouble();
				if(rand < failureRate){
					currentRoundEvents.add(new Event(i, Event.EventType.FAILURE));
				}
			}
			
			addrCopy = crashedNodes.toArray(new Integer[0]);
			for(Integer i: addrCopy){
				double rand = randNumGen.nextDouble();
				if(rand < recoveryRate){
					currentRoundEvents.add(new Event(i, Event.EventType.START));
				}
			}
		}else{
			try{
				printLiveDead();
				String input;
				
				if(!nodes.isEmpty()){
					System.out.println("Crash which nodes? (space-delimited list of addresses or just press enter)");
					input = keyboard.readLine().trim();
					if(!input.equals("")){
						String[] crashList = input.split("\\s+");
						for(String s: crashList){
							currentRoundEvents.add(new Event(Integer.parseInt(s), Event.EventType.FAILURE));
						}
					}
				}
				
				// The user could also just use the start command, but not if the input method is file
				if(!crashedNodes.isEmpty()){
					System.out.println("Restart which nodes? (space-delimited list of addresses or just press enter)");
					input = keyboard.readLine().trim();
					if(!input.equals("")){
						String[] restartList = input.split("\\s+");
						for(String s: restartList){
							currentRoundEvents.add(new Event(Integer.parseInt(s), Event.EventType.START));
						}
					}
				}
			}catch(IOException e){
				e.printStackTrace(System.err);
			}
		}
	}

	/**
	 * Check to see if any timeouts are supposed to fire during the current
	 * time step
	 */
	private void checkTimeouts(ArrayList<Event> currentRoundEvents) {
		ArrayList<Timeout> currentTOs = waitingTOs;
		waitingTOs = new ArrayList<Timeout>();
		
		Iterator<Timeout> iter = currentTOs.iterator();
		while(iter.hasNext()) {
			Timeout to = iter.next();
			if(now() >= to.fireTime && !canceledTimeouts.contains(to)) {
				iter.remove();
				currentRoundEvents.add(new Event(to));
			}
		}
		
		waitingTOs.addAll(currentTOs);
		waitingTOs.removeAll(canceledTimeouts);
	}
	
	/**
	 * Reorders and executes all the events for the current round.
	 * 
	 * Note that commands can be executed in a different order than they appear
	 * in the command file!
	 * 
	 * @param currentRoundEvents
	 */
	private void executeEvents(ArrayList<Event> currentRoundEvents) {
		if(userControl == FailureLvl.EVERYTHING){
			boolean doAgain = false;
			do{
				try{
					for(int i = 0; i < currentRoundEvents.size(); ++i){
						System.out.println(i + ": " + currentRoundEvents.get(i).toString());
					}
					System.out.println("In what order should the events happen? (enter for in-order)");
					String input = keyboard.readLine().trim();

					if(input.equals("")){
						for(Event ev: currentRoundEvents){
							handleEvent(ev);
						}
					}else{
						String[] order = input.split("\\s+");

						HashSet<Event> dupeMissCheck = new HashSet<Event>();
						for(String s: order){
							dupeMissCheck.add(currentRoundEvents.get(Integer.parseInt(s)));
						}
						
						if(dupeMissCheck.size() != currentRoundEvents.size()) {
							System.out.println("Not all of the events were specified!");
							doAgain = true;
							continue;
						}
						
						for(String s: order){
							Event ev = currentRoundEvents.get(Integer.parseInt(s));
							handleEvent(ev);
						}
					}
				}catch(IOException e){
					e.printStackTrace(System.err);
					doAgain = true;
				}
			}while(doAgain);
		}else{
			Collections.shuffle(currentRoundEvents, randNumGen);
			System.out.println("Executing with order: ");
			for(Event ev: currentRoundEvents) {
				System.out.println(ev.toString());
				handleEvent(ev);
			}
		}
	}

	/**
	 * Process an event.
	 * 
	 * @param ev
	 *            The event that should be processed
	 * @return True if we should advance time, False otherwise
	 */
	private void handleEvent(Event ev){
		switch(ev.t){
		case FAILURE:
			failNode(ev.node);
			break;
		case START:
			startNode(ev.node);
			break;
		case EXIT:
			stop();
			break;
		case COMMAND:
			sendNodeCmd(ev.node, ev.command);
			break;
		case ECHO:
			parser.printStrArray(ev.msg, System.out);
			break;
		case DELIVERY:
			deliverPkt(ev.p.getDest(), nodes.get(ev.p.getDest()), ev.p.getSrc(), ev.p);
			break;
		case TIMEOUT:
			try{
				ev.to.cb.invoke();
			}catch(InvocationTargetException e) {
				Throwable t = e.getCause();
				if(t == null) {
					e.printStackTrace(System.err);
				} else if(t instanceof NodeCrashException) {
					// let it slide
				} else {
					t.printStackTrace(System.err);
				}
			}catch(IllegalAccessException e) {
				e.printStackTrace(System.err);
			}
			break;
		default:
			System.err.println("Shouldn't happen. TIME here?");
		}
	}

	/**
	 * Check whether a given node is live and therefore valid to give msgs/cmds.
	 * Additionally, an error message will print out if the address itself is
	 * invalid.
	 * 
	 * @param nodeAddr
	 *            The node for which we want to check validity
	 * @return true If the node is alive, false if not.
	 */
	private boolean isNodeValid(int nodeAddr) {
		// up and running valid node
		if(nodes.containsKey(nodeAddr)) {
			return true;
		}
		
		// node is crashed but addr is still valid
		if(crashedNodes.contains(nodeAddr)){
			return false;
		}
		
		// the node address is invalid
		System.err.println("Node address " + nodeAddr + " is invalid.");
		return false;
	}

	/**
	 * Print out a list of live and crashed nodes in a human-readable way.
	 */
	private void printLiveDead() {
		if(!nodes.isEmpty()){
			Iterator<Integer> iter = nodes.keySet().iterator();
			StringBuffer live = new StringBuffer();
			// its not empty so we know it hasNext()
			live.append(iter.next());
			
			while(iter.hasNext()){
				live.append(", " + iter.next());
			}
			
			System.out.println("Live nodes: " + live.toString());
		}
		
		if(!crashedNodes.isEmpty()){
			Iterator<Integer> iter = crashedNodes.iterator();
			StringBuffer dead = new StringBuffer();
			// its not empty so we know it hasNext()
			dead.append(iter.next());
			
			while(iter.hasNext()){
				dead.append(", " + iter.next());
			}
			
			System.out.println("Dead nodes: " + dead.toString());
		}
	}

	/**
	 * Actually deliver an in transit packet to its intended destination.
	 * 
	 * @param destAddr
	 *            The address of the recipient
	 * @param destNode
	 *            The node object of the recipient
	 * @param srcAddr
	 *            The address of the sender
	 * @param pkt
	 *            The packet that should be delivered
	 * @return True if the packet was delivered, false if not
	 */
	private boolean deliverPkt(int destAddr, Node destNode, int srcAddr, Packet pkt) {
		if(!isNodeValid(destAddr)) {
			return false;
		}
		
		try{
			destNode.onReceive(srcAddr, pkt.getProtocol(), pkt.getPayload());
		}catch(NodeCrashException e) { }
		
		return true;
	}
}