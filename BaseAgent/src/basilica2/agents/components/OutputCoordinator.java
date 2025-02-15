package basilica2.agents.components;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.time.LocalDateTime;

import org.jivesoftware.smack.packet.Message;

import basilica2.agents.events.MessageEvent;
import basilica2.agents.events.PrivateMessageEvent;
import basilica2.agents.events.priority.AbstractPrioritySource;
import basilica2.agents.events.priority.PriorityEvent;
import basilica2.agents.components.StateMemory;
import basilica2.agents.data.State;
import basilica2.util.MessageEventLogger;
import basilica2.util.Timer;
import edu.cmu.cs.lti.basilica2.core.Agent;
import edu.cmu.cs.lti.basilica2.core.Component;
import edu.cmu.cs.lti.basilica2.core.Event;
import edu.cmu.cs.lti.project911.utils.log.Logger;
import edu.cmu.cs.lti.project911.utils.time.TimeoutReceiver;
import smartlab.communication.CommunicationManager; 
// import org.zeromq.SocketType;
// import org.zeromq.ZMQ;
// import org.zeromq.ZContext;

/**
 * @author dadamson
 */
public class OutputCoordinator extends Component implements TimeoutReceiver
{
	public static final int HISTORY_SIZE = 3;

	private Agent agent; 
	final private ArrayList<PriorityEvent> proposalQueue = new ArrayList<PriorityEvent>();
	private double delay = 2.0;
	private ArrayList<AbstractPrioritySource> recentSources = new ArrayList<AbstractPrioritySource>();
	private Map<String, AbstractPrioritySource> activeSources = new HashMap<String, AbstractPrioritySource>();

	public static final double LEAST_PRIORITY = 0.001;
	public static final double LOW10_PRIORITY = 0.1;
	public static final double LOW15_PRIORITY = 0.15;
	public static final double LOW_PRIORITY = 0.25;
	public static final double MEDIUM_PRIORITY = 0.5;
	public static final double HIGH_PRIORITY = .75;
	public static final double HIGHEST_PRIORITY = 1.0;

	private Boolean outputMultimodal = false; 
	private Boolean outputToPSI = false; 
	private Boolean separateOutputToPSI = false;
	CommunicationManager psiCommunicationManager; 
// 	ZeroMQClient psiCommunicationManager; 
//  private ZMQ.Socket publisher;
//  ZContext context; 
// 	private String psiHost = "*";							// This machine 
// 	private String psiPort = "5555"; 
	private String bazaarToPSITopic = "Bazaar_PSI_Text";
	private Boolean dontListenWhileSpeaking = true; 
	private Integer multimodalWordsPerMinute;
	private Double multimodalWordsPerSecond; 
	private Double multimodalConstantDelay; 
	private String lastStepName=null;
	private String removeStepName=null;
	
	public OutputCoordinator(Agent agent, String s1, String s2)
	{
		// s1 = name; s2 = properties file name
		super(agent, s1, s2);
		this.agent = agent; 
		Timer timer = new Timer(delay, "Output Queue", this);
		timer.start();
		
		if(myProperties!=null) {
			try{outputMultimodal = Boolean.parseBoolean(myProperties.getProperty("output_multimodal", "false"));}
			catch(Exception e) {e.printStackTrace();}
			try{outputToPSI = Boolean.parseBoolean(myProperties.getProperty("output_to_PSI", "false"));}
			catch(Exception e) {e.printStackTrace();}
			try{separateOutputToPSI = Boolean.parseBoolean(myProperties.getProperty("separate_output_to_PSI", "false"));}
			catch(Exception e) {e.printStackTrace();}
			try{dontListenWhileSpeaking = Boolean.parseBoolean(myProperties.getProperty("dont_listen_while_speaking", "true"));}
			catch(Exception e) {e.printStackTrace();}
			try{multimodalWordsPerMinute = Integer.valueOf(myProperties.getProperty("multimodal_words_per_minute", "150"));}
			catch(Exception e) {e.printStackTrace();}
			try{multimodalConstantDelay = Double.valueOf(myProperties.getProperty("multimodal_words_constant_delay", "0.5"));}
			catch(Exception e) {e.printStackTrace();}
			try{bazaarToPSITopic = myProperties.getProperty("Bazaar_to_PSI_Topic", bazaarToPSITopic);}
			catch(Exception e) {e.printStackTrace();}
// 			try{psiHost = myProperties.getProperty("PSI_Host", psiHost);}
// 			catch(Exception e) {e.printStackTrace();}
// 			try{psiPort = myProperties.getProperty("PSI_Port", psiPort);}
// 			catch(Exception e) {e.printStackTrace();}
		}
		// multimodalWordsPerSecond = (int)Math.ceil(multimodalWordsPerMinute/60.0); 
		multimodalWordsPerSecond = multimodalWordsPerMinute/60.0; 
		if (outputToPSI) {
			initializePSI(); 			
		}
	}
	
	private void initializePSI() {
		psiCommunicationManager = new CommunicationManager();
// 		context = new ZContext(); 
// 		try {                      
// 	    	publisher = context.createSocket(SocketType.PUB);
// 	        // publisher.bind("tcp://*:5555");  
// 	        publisher.bind("tcp://" + psiHost + ":" + psiPort);  
// 		} catch (Exception e) {
// 			e.printStackTrace();
//		}
	}

	public void addAll(Collection<PriorityEvent> events)
	{
		synchronized (proposalQueue)
		{
			proposalQueue.addAll(events);
			//log(Logger.LOG_NORMAL, "addAll to Proposal Queue: " + proposalQueue);
		}
	}

	@Override
	protected void processEvent(Event event)
	{
		throw new UnsupportedOperationException("Not supported anymore.");
	}

	@Override
	public String getType()
	{
		return "COORDINATOR";
	}

	public void timedOut(String id)
	{
		synchronized (proposalQueue)
		{
			if (!proposalQueue.isEmpty())
			{

				log(Logger.LOG_NORMAL, "==================== Proposal Queue ==========================");
				cleanUp();

				PriorityEvent best = null;
				double bestBelief = 0;

				for (PriorityEvent p : proposalQueue)
				{
					double belief = beliefGivenHistory(p);
					double d = belief * p.getPriority();

					log(Logger.LOG_NORMAL, "EventType: "+p.getEventType()+ " StepName: "+p.getMicroStepName()+ " belief*priority: " + belief + "*" + p.getPriority() + "=" + d + " p="+p);

					if (d > 0 && (d > bestBelief))
					{
						best = p; // proposal with the highest priority
						bestBelief = d;
					}

				}

				if (best != null)
				{
					log(Logger.LOG_NORMAL, "Execute: " + best);
					// if the proposal about to be executed belongs to a new step, 
					// set removeStepName which is used in cleanUp() to remove micro_local proposals belonging to this step 
					if (lastStepName!=null && (!best.getMicroStepName().equals(lastStepName)))
					{
						removeStepName = lastStepName;
						log(Logger.LOG_NORMAL, "removeStepName: " + removeStepName);
					}
					lastStepName = best.getMicroStepName();
					log(Logger.LOG_NORMAL, "lastStepName: " + lastStepName);
					
					best.getCallback().accepted(best);
					publishEvent(best.getEvent());
					proposalQueue.remove(best);

					AbstractPrioritySource source = best.getSource();

					activeSources.put(source.getName(), source);
					// keep the size of recentSources <= HISTORY_SIZE
					if (recentSources.size() >= HISTORY_SIZE) recentSources.remove(0);

					recentSources.add(source); 
				}
				log(Logger.LOG_NORMAL, "==================== DONE ==========================");
			}
		}

		new Timer(delay, "Output Queue", this).start();
	}

	private void cleanUp()
	{
		Iterator<PriorityEvent> pit = proposalQueue.iterator();
		long now = Timer.currentTimeMillis();

		synchronized (proposalQueue)
		{
			while (pit.hasNext())
			{
				PriorityEvent p = pit.next();
				
				if (p.getInvalidTime() < now && !p.getEventType().equals("macro")) 
				{
					// remove timeout micro proposals
					log(Logger.LOG_NORMAL, "cleanUp micro timeout: " + p);
					p.getCallback().rejected(p);
					pit.remove();

					// String sourceName = p.getSource().getName();
					// if(activeSources.containsKey(sourceName))
					// {
					// activeSources.remove(sourceName); //this is weird -
					// presumes only one active source of the same name at once.
					// }
				}else if (p.getEventType().equals("micro_local") && removeStepName!=null && p.getMicroStepName().equals(removeStepName))
				{
					// remove passed step's micro_local proposals
					log(Logger.LOG_NORMAL, "cleanUp micro_local removeStepName: " + p);
					p.getCallback().rejected(p);
					pit.remove();
					removeStepName=null;
				}
			}

			Iterator<String> pat = new ArrayList<String>(activeSources.keySet()).iterator();
			while (pat.hasNext())
			{
				String key = pat.next();
				AbstractPrioritySource source = activeSources.get(key);

				if (!source.isBlocking())
				{
					activeSources.remove(key);
				}
			}
		}
	}

	protected void publishEvent(Event e)
	{
		//log(Logger.LOG_NORMAL, "publishEvent: " + e);
		if (e instanceof MessageEvent)
		{
			publishMessage((MessageEvent) e);
		}
		else
		{
			broadcast(e);
		}
	}

	private void publishMessage(MessageEvent me)
	{
		// When a message comes in, tell the Actor to start typing
		// (Future) If other messages pile up while still typing, run some rules
		// to delete/re-order certain messages
		// Might be a better idea to merge output coordinator and actor, or
		// connect them directly
				
		String withinPromptDelimiter = "|||"; 
		String messageText; 
		if (!me.getText().contains(withinPromptDelimiter))
		{
			if ((!outputToPSI) || (separateOutputToPSI)) {
				if (outputMultimodal) {
					messageText = formatMultimodalMessage(me);
					me.setText(messageText);					
				}
				broadcast(me);
			}		
			if (outputToPSI) {
				publishMessageToPSI(me);
			}
			MessageEventLogger.logMessageEvent(me);
		}

		else
		{
			String[] messageParts = me.getParts();
			String[] allAnnotations = me.getAllAnnotations();
			for (int i = 0; i < messageParts.length; i++)
			{
				String mappedPrompt = messageParts[i].trim();
				MessageEvent newme;
				try
				{
					newme = me.cloneMessage(mappedPrompt);
				}
				catch (Exception e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
					newme = new MessageEvent(this, me.getFrom(), mappedPrompt, allAnnotations);
				}

				if (me.isAcknowledgementExpected())
				{
					if (i == (messageParts.length - 1))
					{
						newme.setAcknowledgementExpected(true);
					}
				}
				newme.setReference(me.getReference());
				if ((!outputToPSI) || (separateOutputToPSI)) {
					if (outputMultimodal) {
						messageText = formatMultimodalMessage(newme);
						newme.setText(messageText);					
					}
					broadcast(newme);			
					MessageEventLogger.logMessageEvent(newme);					
				}
				if (outputToPSI) {
					publishMessageToPSI(newme);			
					try       											// Don't send message parts too quickly
					{
						System.err.println("Sleeping for 5s before sending next part of message");
						Thread.sleep(5000);
						tick();
					}
					catch (Exception e)
					{
						log(Logger.LOG_WARNING, "<warning>Throttling problem</warning>");
						e.printStackTrace();
					}
				}
			}
		}
	}

	
	private void publishMessageToPSI(MessageEvent me)
	{
		String text = me.getText();	
		String messageString = formatMultimodalMessage(me); 
//		System.err.println("OutputCoordinator, publishMessagetoPSI, message: " + messageString);
		setMultimodalDontListenWhileSpeaking(text); 
		if (!separateOutputToPSI) {
			MessageEvent newme;
			String[] allAnnotations = me.getAllAnnotations();
			try
			{
				newme = me.cloneMessage(messageString);
			}
			catch (Exception e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
				newme = new MessageEvent(this, me.getFrom(), messageString, allAnnotations);
			}
			broadcast(newme);
			MessageEventLogger.logMessageEvent(newme);
			psiCommunicationManager.msgSender(bazaarToPSITopic,messageString);
		} else {
			psiCommunicationManager.msgSender(bazaarToPSITopic,messageString);
		}
		
// 		String topicMessage = bazaarToPSITopic + ":true" + multiModalDelim + messageString; 
// 		System.err.println("OutputCoordinator, publishMessageToPSI, topic message: " + topicMessage);
//         publisher.send(topicMessage, 0);
	}
	
	
// Sample formatted multimodal message follows. Currently, only the tags 'from', 'to', and 'speech' are supported for output. 
// multimodal:::true;%;from:::Jamie;%;to:::group;%;speech:::"I'm Jamie";%;location:::30:::60:::90;%;facialExp:::smile;%;pose:::sitting;%;emotion:::happy
	private String formatMultimodalMessage(MessageEvent me)
	{
		String multiModalField = "multimodal";  
		String fromField = "from";
		String toField = "to";
		String speechField = "speech";
		// String locationField = "location";
		// String location = null; 
	    String multiModalDelim = ";%;";
		String withinModeDelim = ":::";	
		String toAllUsers = "group";
		String messageString; 
		
		String text; 
		String from = agent.getUsername();
		String to; 
		String textOrig = me.getText();	
		
		// Special processing for Mob Programming 
		if (textOrig.contains("Navigator::"))
		{
			text = textOrig.substring(11);
			to = "navigator";
		} else if (textOrig.contains("Driver::"))
		{
			text = textOrig.substring(8);
			to = "driver";
		} else if (textOrig.contains("Group::"))
		{
			text = textOrig.substring(8);
			to = "group";
		} else {
			text = textOrig; 
			// To specific user if known.
			to = me.getDestinationUser();
			if (to == null) {
				to = toAllUsers; 
			}	
		}
		messageString = multiModalField + withinModeDelim + "true" + multiModalDelim + fromField + withinModeDelim + from + multiModalDelim + toField + withinModeDelim + to + multiModalDelim + speechField + withinModeDelim + text; 			
		System.err.println("OutputCoordinator, formatMultimodalMessage, message: " + messageString);
		return messageString; 
	}	
	
	public void setMultimodalDontListenWhileSpeaking(String speechString) {
		State olds = StateMemory.getSharedState(agent); 
		if (dontListenWhileSpeaking) {
			State news;
			if (olds != null)
				news = State.copy(olds);
			else
				news = new State();
			LocalDateTime now = LocalDateTime.now();
			System.err.println("=== OutputCoordinator, don't listen while speaking -- now:     " + now.toString() + " <<<"); 
			Double sentenceDelay = speechString.split(" ").length/multimodalWordsPerSecond; 
			Double pauseSecondsDouble = multimodalConstantDelay + sentenceDelay;
			Long pauseSeconds = (long)Math.ceil(pauseSecondsDouble); 
			LocalDateTime dontListenEnd = now.plusSeconds(pauseSeconds); 
			news.setMultimodalDontListenWhileSpeakingEnd(dontListenEnd);
			System.err.println("=== OutputCoordinator, don't listen while speaking -- dontListenEnd: " + dontListenEnd.toString() + " <<<"); 
			StateMemory.commitSharedState(news, agent);
		}
	}

	
	public void log(String from, String level, String msg)
	{
		log(level, msg);
	}

	private double beliefGivenHistory(PriorityEvent p)
	{
		double belief = 1.0;

		for (AbstractPrioritySource source : activeSources.values())
		{
			if (!source.allows(p))
			{
				log(Logger.LOG_NORMAL, "Proposal not allowed by " + source + ": " + p);
				belief = 0;
				return belief;
			}
		}
		// macro proposal's priority is not affected by recent executed proposals
		if (p.getEventType().equals("macro"))
		{
			return belief;
		}
		// micro proposal's priority can be affected by recent executed proposals
		for (AbstractPrioritySource source : recentSources)
		{
			Double likelihood = source.likelyNext(p);
			log(Logger.LOG_NORMAL, "beliefGivenHistory: recentSources=" + source +" on p="+p.getSource().getName()+ " impact=" + likelihood);
			belief *= likelihood;
		}
		// log(Logger.LOG_NORMAL,("belief = "+belief + " for "+p);
		return belief;
	}

	void addProposal(PriorityEvent pe)
	{
		synchronized (proposalQueue)
		{
			proposalQueue.add(pe);
			//log(Logger.LOG_NORMAL, "after addProposal: " + proposalQueue);
		}
	}
}
