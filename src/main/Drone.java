package main;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.TreeMap;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

// classe Drone
/**
 * <b>Drone est la classe représentant un individu de la flotte.</b>
 * <p>Un membre du SDZ est caractérisé par les informations suivantes : </p>
 * <ul>
 * 	<li>Un identifiant unique.</li>
 * 	<li>Sa position actuelle.</li>
 *	<li>La position que le drone veut atteindre.</li>
 * 	<li>L'état du drone.</li>
 * 	<li>Un Map qui contient la liste des membres de la flotte.<>
 * </ul>
 * 
 * @see Constants#State
 */
public class Drone extends Agent
{
	private static final long serialVersionUID = 1L;
	
	// l'id du drone
	/**
	 * L'ID du drone est fixé à l'initialisation du drone, il sert à identifier le drone et à savoir s'il est un maître ou pas.
	 * @see Drone#isMaster
	*/
	int m_id;
	
	// sa position actuelle (x, y)
	
	/**
	 * C'est la position où le drone se trouve à chaque instant.
	 * 
	 * @see Position
	*/
	Position m_position;
	
	// l'objectif initial du drone (en terme de position à atteindre)
	/**
	 * C'est la position que le drone veut attaindre.
	 * @see Position
	*/
	Position m_goal;
	
	// l'état du drone
	/**
	 * C'est l'état actuel du drone par rapport à la flotte : seul, flotte, fusion, etc.
	 * Pour connaître tous les possibles états d'un drone, regardez la documentation de Constants.State
	 * @see Constants#State 
	 * @see Drone#goalInFleet
	*/
	Constants.State m_state;
	
	/**
	 * C'est la liste de drones qu'appartiennent à la flotte de ce drone|, y compris la position actuele de chaqu'un.
	 * Le maître de la flotte est l'élément 0 de la liste.
	 * 
	 * @see Drone#idIsMaster
	*/
	Map<Integer, Position> m_fleet;
	
	// Portals variables
	
	Map<String, Position> m_knownPortalsPositions;
	
	Map<String, Integer> m_knowPortalsNbDronesAccepted;
	
	// queue used to know prevent masters from picking the same portal over and over
	Queue<String> m_portalsQueue;
	
	String m_destinationPortalName; // le nom du portail vers lequel il doit se diriger (choisi par son maitre)
	
	String m_portalPassword; // mot de passe à utiliser lors de la rencontre avec le portail choisi
	
	// -------
	
	/**
	 * C'est un nombre indicant le moment de la dernière réception d'un message.
	*/
	long m_lastReception;
	
	long m_lastPortalProposal;
	
	String m_lastPortalProposalPortalName;
	
	/**
	 * C'est le constructeur de la classe, tout drone créé est initialisé comme ALONE,
	 * sa dernière réception prend la valeur de l'instant de création, la position de destin et d'origine
	 * doivent être fournies pendant la création du drone,
	 * ce drone est le premier élément ajouté à la liste de membres de la flotte.
	 * 
	 * Dans cette méthode, on ajoute également les comportements du drone.
	 * 
	 * @param m_lastReception
	 * 		Instant de la dernière réception d'un message
	 * @param m_id
	 * 		ID de ce drone
	 * @param m_position
	 * 		Position initial de ce drone
	 * @param m_goal
	 * 		Position destin originale de ce drone
	 * @see Position
	 * @see Constants.State
	 * @see RespondToDisplay
	 * @see EmitEnvironment
	 * @see	ReceiveEnvironment
	 * @see Movement
	*/
	protected void setup()
	{
		// on récupère les paramètres passés lors de sa création
		Object[] arguments = this.getArguments();

		m_lastReception = System.currentTimeMillis();
		m_id = (int) arguments[0];
		m_position = (Position) arguments[1];
		m_goal = (Position) arguments[2];
		m_state = Constants.State.ALONE;
		m_fleet = new TreeMap<Integer, Position>();
		m_fleet.put(new Integer(m_id), m_position);
		m_knownPortalsPositions = new HashMap<String, Position>();
		m_knowPortalsNbDronesAccepted = new HashMap<String, Integer>();
		m_portalsQueue = new PriorityQueue<String>(Constants.m_numberDrones);
		m_destinationPortalName = "";
		m_portalPassword = "";
		m_lastPortalProposal = -Constants.m_timeToWaitBeforeNextPortalProposal;
		m_lastPortalProposalPortalName = null;
		
		addBehaviour(new RespondToDisplay(this));
		addBehaviour(new EmitEnvironment(this, Constants.m_emitEnvironmentPeriod));
		addBehaviour(new ReceiveEnvironment(this));
		addBehaviour(new ReceivePortalsInfos(this));
		addBehaviour(new ReceiveMasterOrder(this));
		addBehaviour(new PortalAccept(this));
		addBehaviour(new PortalRefuse(this));
		addBehaviour(new Movement(this, Constants.m_movementPeriod));
		addBehaviour(new CheckPortalPossibility(this, Constants.m_emitEnvironmentPeriod));
		addBehaviour(new ListenAcceptPortalProposal(this));
		addBehaviour(new ListenRejectPortalProposal(this));
		addBehaviour(new ListenDroneLeaving(this));
	}

	// savoir si l'on est le master
	/**
	 * Vérifie si l'on est le maître.
	 * @return La valeur de la vérification.
	 * @see Drone#m_fleet
	*/
	boolean isMaster()
	{
		if( !m_fleet.keySet().isEmpty() && ((Integer) m_fleet.keySet().toArray()[0]).intValue() == m_id)
			return true;
		
		return false;
	}
	
	/**
	 * Vérifie si l'on est le deuxième.
	 * @return La valeur de la vérification.
	 * @see Drone#m_fleet
	*/
	boolean isSecond()
	{
		if(((Integer) m_fleet.keySet().toArray()[1]).intValue() == m_id)
			return true;
		
		return false;
	}
	
	boolean isActive()
	{
		return !(m_state.equals(Constants.State.DEAD) || m_state.equals(Constants.State.ARRIVED));
	}
	
	// méthode qui permet d'encoder les paramètres du drones au format JSON
	/**
	 * Retourne le drone sous forme d'une chaîne JSON.
	 * @return La chaîne JSON contenant l'information de ce drone.
	*/
	@SuppressWarnings("unchecked")
	String toJSONArray()
	{
		//Objet qui contiendra la sérialisation du drone
		JSONArray args = new JSONArray();

		// on sérialise l'id
		JSONObject id = new JSONObject();
		id.put("id", m_id);
		args.add(id);
		
		// on sérialise la position dans un objet JSON  position et après on rajoute cet objet à args
		JSONObject position = m_position.toJson();
		args.add(position);
		
		//On rajoute chaque drone de la flotte à la sérialisation
		JSONArray fleet = new JSONArray();
		for(Map.Entry<Integer, Position> entry : m_fleet.entrySet())
		{			
			// on sérialise l'id
			id = new JSONObject();
			id.put("id", entry.getKey());
			
			// on sérialise la position
			position = entry.getValue().toJson();
			
			JSONArray value = new JSONArray();
			value.add(id);
			value.add(position);
			fleet.add(value);
		}
		args.add(fleet);
		
		// on renvoie le JSON en string
		return args.toJSONString();
	}
	
	@SuppressWarnings("unchecked")
	String knownPortalsToJson()
	{
		JSONArray args = new JSONArray();
		for(Map.Entry<String, Position> entry : m_knownPortalsPositions.entrySet())
		{
			JSONObject portalJson = new JSONObject();
			portalJson.put("name", entry.getKey());
			JSONObject portalPosition = entry.getValue().toJson();
			portalJson.put("position", portalPosition);
			
			int nbDronesAccepted = m_knowPortalsNbDronesAccepted.get(entry.getKey()).intValue();
			portalJson.put("nbDronesAccepted", nbDronesAccepted);
			args.add(portalJson);
		}
		return args.toJSONString();
	}
	
	// méthode qui génère une case du terrain et l'affecte à l'objectif
	/**
	 * Permet d'affecter le drone à une destination (Position) sélectionée aléatoirement.
	 * @see Position#random
	*/
	public void generateGoal()
	{
		m_goal.setPosition(Position.random());
	}

	// renvoit vrai si l'objectif du drone a été atteint
	/**
	 * Permet savoir si l'on a atteint le destin, i.e., si la position actuelle est égale à la position destin.
	 * @return Le résultat de la comparaison
	 * @see Drone#m_position
	 * @see Drone#m_goal
	*/
	public boolean reachedGoal()
	{
		if(m_position.equals(m_goal))
			return true;
		
		return false;
	}
	
	/**
	 * Permet savoir quelle est le drone qui se trouve avant nous dans la liste de la flotte.
	 * Si l'on est le maître elle retourne notre propre ID.
	 * @return L'ID du drone qui se trouve avant nous dans la liste.
	 * @see Drone#m_fleet
	*/
	public Integer nextInFleet()
	{
		if(isMaster())
			return m_id;

		Object[] keys = m_fleet.keySet().toArray();

		for(int i = 0 ; i < keys.length ; i ++)
		{
			Integer key = (Integer) keys[i];
			if(m_id == key.intValue())
				return (Integer) keys[i - 1];
		}
		
		// erreur
		return -1;
	}
	
	/**
	 * Permet savoir la position destin de ce drone
	 * Si l'on est le maître elle retourne la position destin originale, c'est-à-dire, comme si le dron était tout seul.
	 * Si l'on n'est pas le maìtre, on calcule la position destin en fonction de la position destin du maître de la flotte.
	 
	 * @return La prochaine destin de ce drone.
	 * @see Drone#m_goal
	 * @see isMaster
	*/
	public Position goalInFleet()
	{
		//int index = getIndexInFleet();
		//int size = m_fleet.size();
		
		// Éventuellement se positionner dans une structure en anneau
		
		Position position = (Position) m_fleet.get(nextInFleet());

		return position;
	}
	
	/**
	 * Permet savoir si un ID donné correspond avec celui de notre maître.
	 * @param id
	 * 	L'ID que l'on veut comparer avec celui de notre maître
	 * @return Le résultat de la comparaison
	 
	 * @see isMaster
	*/
	public boolean idIsMaster(int id)
	{
		Object[] keys = m_fleet.keySet().toArray();

		if(((Integer)keys[0]).intValue() == id)
			return true;
		
		return false;
	}
	
	/**
	 * Permet connaître l'index que l'on occupe dans la liste d'éléments de la flotte.
	 * @return L'index que l'on occupe parmi les éléments de la flotte.
	*/
	public int getIndexInFleet()
	{
		Object[] keys = m_fleet.keySet().toArray();

		for(int i = 0 ; i < keys.length ; i ++)
		{
			Integer key = (Integer) keys[i];
			if(m_id == key.intValue())
				return i;
		}
		
		// erreur
		return -1;
	}
	
	/**
	 * Permet remplacer le maître si le temps passé est supérieur à 3000 millisecondes.
	*/
	public void updateMaster()
	{
		if(!m_fleet.isEmpty() && (System.currentTimeMillis() - m_lastReception) > 3000)
		{
			m_fleet.remove(m_fleet.keySet().toArray()[0]);
		}
		
		//
		
	//	if(m_fleet.size() == 1)
		//	m_state = Constants.State.ALONE;
	}
		
}

// behaviour qui r�pond � Display quand il lui demande quelque chose
class RespondToDisplay extends CyclicBehaviour
{
	private static final long serialVersionUID = 1L;
	
	Drone m_drone;

	public RespondToDisplay(Drone drone) 
	{
		m_drone = drone;
	}

	public void action() 
	{	
		ACLMessage message = m_drone.receive(MessageTemplate.MatchSender(new AID("Display", AID.ISLOCALNAME)));
		
		// si on a bien re�u un message de Display, on lui r�pond avec un INFORM
		// dans lequel on envoie les informations en question au format JSON
		if(message != null)
		{
			ACLMessage reply = message.createReply();
			reply.setPerformative(ACLMessage.INFORM);
			reply.setContent(m_drone.toJSONArray());
			
			m_drone.send(reply);
		}
		else
			block();
	}
}

// behaviour qui �met des caract�ristiques du drone en permanence
class EmitEnvironment extends TickerBehaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public EmitEnvironment(Agent agent, long period) 
	{
		super(agent, period);

		m_drone = (Drone) agent;
	}

	protected void onTick() 
	{
		if (!m_drone.isActive()) return;
		
		ACLMessage message = new ACLMessage(ACLMessage.INFORM);

		message.setContent(m_drone.toJSONArray());
		
		// on envoit � tous les drones sauf soi-m�me
		for(int i = 0 ; i < Constants.m_numberDrones ; i ++)
			if(i != m_drone.m_id)
				message.addReceiver(new AID("Drone" + i, AID.ISLOCALNAME));
		
		m_drone.send(message);
	}
}

// behaviour qui analyse les messages re�us des autres drones
class ReceiveEnvironment extends Behaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public ReceiveEnvironment(Drone drone) 
	{
		m_drone = drone;
	}

	@SuppressWarnings("unchecked")
	public void action() 
	{
		ACLMessage message = m_drone.receive(MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.not(MessageTemplate.MatchConversationId("portals"))));
		
		if(message != null)
		{	
			if (message.getSender().getLocalName().substring(0, 5).equals("Drone")) // the sender is a drones
			{
				Map<String, Object> parameters = Constants.fromJSONArray(message.getContent());
				Position position = (Position) parameters.get("position");
				
				// le drone �metteur est proche
				if(m_drone.m_position.reachable(position, Constants.m_maxRange))
				{	
					int id = (int) parameters.get("id");
					
					if(!m_drone.m_fleet.containsKey(new Integer(id)))
					{
						ACLMessage reply = new ACLMessage(ACLMessage.INFORM);
						reply.setContent(m_drone.toJSONArray());
						reply.addReceiver(new AID("Drone" + id, AID.ISLOCALNAME));
						m_drone.send(reply);
					}
					else
					{
						if(m_drone.idIsMaster(id))
							m_drone.m_lastReception = System.currentTimeMillis();
					}
					
					if(Constants.m_collisionActivated && m_drone.m_position.equals(position) && !m_drone.m_state.equals(Constants.State.ENTERING_PORTAL) && !m_drone.m_state.equals(Constants.State.ARRIVED))
					{
						System.out.println("collision");
						ACLMessage deathMessage = new ACLMessage(ACLMessage.FAILURE);
						deathMessage.addReceiver(new AID("Display", AID.ISLOCALNAME));
						deathMessage.setContent(m_drone.toJSONArray());
						for(Map.Entry<Integer, Position> entry : m_drone.m_fleet.entrySet())
							deathMessage.addReceiver(new AID("Drone"+entry.getKey(), AID.ISLOCALNAME));
						m_drone.m_state = Constants.State.DEAD;
						System.out.println("drone "+m_drone.m_id+" is dead");
						m_drone.send(deathMessage);
						return;
					}
					
					Map<Integer, Position> fleet = (Map<Integer, Position>) parameters.get("fleet");
					//System.out.println("Drone " + m_drone.m_id + " : " + m_drone.m_fleet + " next : " + m_drone.nextInFleet() 
					//+ " " + m_drone.m_state.toString());
					
					switch(m_drone.m_state)
					{
						case ALONE :
							m_drone.m_state = Constants.State.FUSION;
							m_drone.m_fleet.putAll(fleet);
						break;
						
						case FUSION :
							m_drone.m_state = Constants.State.FLEET;
						break;
						
						case FLEET :
							m_drone.m_fleet.putAll(fleet);
							m_drone.m_fleet.replace(new Integer(id), position);
							
							// propage les infos des portails connus à tous les drones de la flotte
							ACLMessage portalsPositionsMessage = new ACLMessage(ACLMessage.INFORM);
							portalsPositionsMessage.setConversationId("portals");
							portalsPositionsMessage.setContent(m_drone.knownPortalsToJson());
							for(Map.Entry<Integer, Position> entry : m_drone.m_fleet.entrySet())
							{
								int droneId = entry.getKey().intValue();
								if (droneId != m_drone.m_id)
								{
									portalsPositionsMessage.addReceiver(new AID("Drone"+droneId, AID.ISLOCALNAME));
								}
							}
							m_drone.send(portalsPositionsMessage);
						break;
						
						default :
						break;
					}
				}
			}
			
			// mémorise l'emplacement des portails rencontrés
			if (message.getSender().getLocalName().substring(0, 6).equals("Portal")) // the sender is a portal
			{
				try
				{
					String portalName = message.getSender().getLocalName();
					JSONParser jsonParser = new JSONParser();
					JSONObject args = (JSONObject) jsonParser.parse(message.getContent());
					JSONObject positionJson = (JSONObject) args.get("position");
					int x = Integer.parseInt((positionJson.get("x")).toString());
					int y = Integer.parseInt((positionJson.get("y")).toString());
					Position position = new Position(x,y);
					
					if(m_drone.m_position.reachable(position, Constants.m_portalMaxRange))
					{
						if (m_drone.m_state == Constants.State.TRAVELING_TO_PORTAL && m_drone.m_destinationPortalName.equals(portalName)) // try to enter into the portal
						{
							ACLMessage queryPortalMessage = new ACLMessage(ACLMessage.QUERY_IF);
							queryPortalMessage.addReceiver(new AID(m_drone.m_destinationPortalName, AID.ISLOCALNAME));
							queryPortalMessage.setContent(m_drone.m_portalPassword);
							m_drone.m_state = Constants.State.WAITING_FOR_PORTAL_AUTORIZATION;
							System.out.println("drone " + m_drone.m_id + "is waiting for autorization");
							// le changement de state va faire que dans le behaviour movement, aucun déplecement ne luis sera assigné, il attendra donc au même endroit la réponse du portail
							m_drone.send(queryPortalMessage);
						} else // memorize portal infos
						{
							if (m_drone.m_state == Constants.State.ENTERING_PORTAL && m_drone.m_destinationPortalName.equals(portalName))
							{
								if (m_drone.m_position.equals(position))
								{
									m_drone.m_state = Constants.State.ARRIVED;
									System.out.println("Drone arrive !!");
									// on envoit un deathMessage de manière analogique à la mort du drone car le comportement de Display est le même
									ACLMessage deathMessage = new ACLMessage(ACLMessage.FAILURE);
									deathMessage.addReceiver(new AID("Display", AID.ISLOCALNAME));
									deathMessage.setContent(m_drone.toJSONArray());
									m_drone.send(deathMessage);
									
									ACLMessage arrivalMessage = new ACLMessage(ACLMessage.CONFIRM);
									arrivalMessage.addReceiver(new AID(portalName, AID.ISLOCALNAME));
									m_drone.send(arrivalMessage);
									return;
								}
							} else
							{
								int nbDronesAccepted = Integer.parseInt(args.get("nbDronesAccepted").toString());
								m_drone.m_knownPortalsPositions.put(portalName, position);
								m_drone.m_knowPortalsNbDronesAccepted.put(portalName, nbDronesAccepted);
								m_drone.m_portalsQueue.add(portalName);
							}
						}
					}
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}
				
			}
		} else
		{
			block();
		}
	}
	
	public boolean done() {
		return !m_drone.isActive();
	}
}

class ReceivePortalsInfos extends Behaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public ReceivePortalsInfos(Drone drone) 
	{
		m_drone = drone;
	}

	public void action() 
	{
		ACLMessage message = m_drone.receive(MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.MatchConversationId("portals")));
		
		if(message != null)
		{	
			try
			{
				JSONParser jsonParser = new JSONParser();
				JSONArray args = (JSONArray) jsonParser.parse(message.getContent());
				for (int i=0; i<args.size(); i++)
				{
					JSONObject portalJson = (JSONObject) args.get(i);
					JSONObject positionJson = (JSONObject) portalJson.get("position");
					int x = Integer.parseInt((positionJson.get("x")).toString());
					int y = Integer.parseInt((positionJson.get("y")).toString());
					Position portalPosition = new Position(x,y);
					String portalName = portalJson.get("name").toString();
					int nbDronesAccepted = Integer.parseInt(portalJson.get("nbDronesAccepted").toString());
					m_drone.m_knownPortalsPositions.put(portalName, portalPosition);
					m_drone.m_knowPortalsNbDronesAccepted.put(portalName, nbDronesAccepted);
					m_drone.m_portalsQueue.add(portalName);
				}
			} catch(Exception e)
			{
				e.printStackTrace();
			}
		} else 
		{
			block();
		}
	}
	
	public boolean done()
	{
		return !m_drone.isActive();
	}
}

class ReceiveMasterOrder extends Behaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public ReceiveMasterOrder(Drone drone) 
	{
		m_drone = drone;
	}

	public void action() 
	{
		ACLMessage message = m_drone.receive(MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
				MessageTemplate.MatchConversationId("portals")));
		
		if(message != null && !m_drone.isMaster())
		{	
			try
			{
				JSONParser jsonParser = new JSONParser();
				JSONObject args = (JSONObject) jsonParser.parse(message.getContent());
				String action = args.get("action").toString();
				String portalName = args.get("name").toString();
				if (action.equals(Constants.Action.GO_TO.toString()))
				{
					String portalPassword = args.get("password").toString();
					if (!m_drone.m_knownPortalsPositions.containsKey("portalName"))
					{
						JSONObject positionJson = (JSONObject) args.get("position");
						int x = Integer.parseInt((positionJson.get("x")).toString());
						int y = Integer.parseInt((positionJson.get("y")).toString());
						Position portalPosition = new Position(x,y);
						int nbDronesAccepted = Integer.parseInt(args.get("nbDronesAccepted").toString());
						m_drone.m_knownPortalsPositions.put(portalName, portalPosition);
						m_drone.m_knowPortalsNbDronesAccepted.put(portalName, nbDronesAccepted);
						m_drone.m_portalsQueue.add(portalName);
					}
					m_drone.m_state = Constants.State.TRAVELING_TO_PORTAL;
					System.out.println("drone "+m_drone.m_id+" is traveling ("+Integer.parseInt(args.get("nbDronesAccepted").toString())+")");
					// send leaving fleet message
					ACLMessage leavingFleetMessage = new ACLMessage(ACLMessage.FAILURE);
					leavingFleetMessage.setContent(m_drone.toJSONArray());
					for(Map.Entry<Integer, Position> entry : m_drone.m_fleet.entrySet())
						leavingFleetMessage.addReceiver(new AID("Drone"+entry.getKey(), AID.ISLOCALNAME));
					m_drone.send(leavingFleetMessage);
					m_drone.m_destinationPortalName = portalName;
					m_drone.m_portalPassword = portalPassword;
					m_drone.m_goal = m_drone.m_knownPortalsPositions.get(portalName);
				} else
				{
					if (action.equals(Constants.Action.DELETE.toString()))
					{
						m_drone.m_knownPortalsPositions.remove(portalName);
						m_drone.m_knowPortalsNbDronesAccepted.remove(portalName);
					}
				}
			} catch(Exception e)
			{
				e.printStackTrace();
			}
		} else 
		{
			block();
		}
	}
	
	public boolean done()
	{
		return !m_drone.isActive();
	}
}

class PortalAccept extends Behaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public PortalAccept(Drone drone) 
	{
		m_drone = drone;
	}

	public void action() 
	{
		ACLMessage message = m_drone.receive(MessageTemplate.MatchPerformative(ACLMessage.AGREE));
		
		if(message != null)
		{
			System.out.println("le portail m'accepte");
			m_drone.m_goal = m_drone.m_knownPortalsPositions.get(m_drone.m_destinationPortalName);
			m_drone.m_state = Constants.State.ENTERING_PORTAL;
		} else 
		{
			block();
		}
	}
	
	public boolean done()
	{
		return !m_drone.isActive();
	}
}

class PortalRefuse extends Behaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public PortalRefuse(Drone drone) 
	{
		m_drone = drone;
	}

	public void action() 
	{
		ACLMessage message = m_drone.receive(MessageTemplate.MatchPerformative(ACLMessage.REFUSE));
		
		if(message != null)
		{
			System.out.println("le portail me refuse");
			m_drone.generateGoal();;
			m_drone.m_state = Constants.State.ALONE;
			m_drone.m_destinationPortalName = "";
			m_drone.m_portalPassword = "";
		} else 
		{
			block();
		}
	}
	
	public boolean done()
	{
		return !m_drone.isActive();
	}
}
	

// classe qui g�re le mouvement d'un drone
class Movement extends TickerBehaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public Movement(Agent agent, long period) 
	{
		super(agent, period);

		m_drone = (Drone) agent;
	}

	protected void onTick() 
	{	
		if (!m_drone.isActive()) return;
		switch(m_drone.m_state)
		{
			case ALONE :
				m_drone.m_position.moveTowards(m_drone.m_goal);
				
				if(m_drone.reachedGoal())
					m_drone.generateGoal();
			break;
			
			case TRAVELING_TO_PORTAL :
				m_drone.m_position.moveTowards(m_drone.m_goal);
			break;
			
			case ENTERING_PORTAL :
				if (m_drone.m_goal != null)
					m_drone.m_position.moveTowards(m_drone.m_goal);
			break;
			
			case FLEET :
				if(m_drone.isMaster())
				{
					if(m_drone.reachedGoal())
						m_drone.generateGoal();
				}
				else
				{
					m_drone.updateMaster();
					m_drone.m_goal = m_drone.goalInFleet();
				}
				if (m_drone.m_goal != null)
					m_drone.m_position.moveTowards(m_drone.m_goal);
			break;
			
			case FUSION :
			break;
			case WAITING_FOR_PORTAL_AUTORIZATION:
			break;
		}
	}
}

// Behaviour du maitre de flotte v�rifiant si des drones peuvent �tre envoy�s � un portail d�couvert.
class CheckPortalPossibility extends TickerBehaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public CheckPortalPossibility(Drone drone, long period)
	{
		super(drone, period);
		m_drone = drone;
	}
	
	@SuppressWarnings("unchecked")
	public void onTick()
	{
		// Seulement � faire si Master
		if (!m_drone.isMaster() || m_drone.m_state.equals(Constants.State.TRAVELING_TO_PORTAL) || m_drone.m_state.equals(Constants.State.ENTERING_PORTAL)) { return; }
		if (m_drone.m_portalsQueue.isEmpty())
		{
			for(Entry<String, Position> entry : m_drone.m_knownPortalsPositions.entrySet())
			{
				m_drone.m_portalsQueue.add(entry.getKey());
			}
		}
		String pickedPortalName = m_drone.m_portalsQueue.poll();
		int iterations = 0;
		while (pickedPortalName != null && !pickedPortalName.isEmpty() && !m_drone.m_knowPortalsNbDronesAccepted.containsKey(pickedPortalName)) // on vérifie que les noms de la file sont d'actualité
		{
			pickedPortalName = m_drone.m_portalsQueue.poll();
		}
		
		while(!m_drone.m_portalsQueue.isEmpty() && iterations < m_drone.m_knowPortalsNbDronesAccepted.size() && m_drone.m_knowPortalsNbDronesAccepted.get(pickedPortalName) > m_drone.m_fleet.size())
		{
			pickedPortalName = m_drone.m_portalsQueue.poll();
			m_drone.m_portalsQueue.add(pickedPortalName); // on réinjecte à la fin le nom choisi
			iterations++;
		}
		if (iterations == m_drone.m_portalsQueue.size())
			return; // no matching portals			

		//System.out.println("drone " + this.m_id + "initiating request to portal" + portalName);
		
		// si nous essayons de demander au même portail depuis un lon moment, et qu'il nous a toujours pas répondu, on en déduit qu'il n'est plus actif, et on le supprime
		if (System.currentTimeMillis() - m_drone.m_lastPortalProposal > Constants.m_timeToWaitBeforeNextPortalProposal && pickedPortalName.equals(m_drone.m_lastPortalProposalPortalName))
		{
			if (!m_drone.m_fleet.isEmpty())
			{
				ACLMessage message = new ACLMessage(ACLMessage.REQUEST);
				message.setConversationId("portals");
									
				JSONObject args = new JSONObject();
				args.put("action", Constants.Action.DELETE.toString());
				args.put("name", pickedPortalName);
				String JSONContent = args.toJSONString();
				
				message.setContent(JSONContent);
								
				for (Map.Entry<Integer, Position> entry : m_drone.m_fleet.entrySet())
				{
					AID droneAID = new AID("Drone" + entry.getKey(), AID.ISLOCALNAME);
					message.addReceiver(droneAID);
				}
				
				m_drone.send(message);
			}
			
			m_drone.m_destinationPortalName = "";
			m_drone.m_knownPortalsPositions.remove(pickedPortalName);
			m_drone.m_knowPortalsNbDronesAccepted.remove(pickedPortalName);
			return;
		}
		// sinon on lui envoie une proposition
		ACLMessage message = new ACLMessage(ACLMessage.PROPOSE);
		message.addReceiver(new AID(pickedPortalName, AID.ISLOCALNAME));
		message.setContent(m_drone.toJSONArray());
		m_drone.send(message);
		m_drone.m_lastPortalProposal = System.currentTimeMillis();
		m_drone.m_lastPortalProposalPortalName = pickedPortalName;
		return;			
	}
}

class ListenAcceptPortalProposal extends Behaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public ListenAcceptPortalProposal(Drone drone)
	{
		super(drone);
		m_drone = drone;
	}
	
	@SuppressWarnings("unchecked")
	public void action()
	{
		ACLMessage answer = m_drone.receive(MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL));
		
		if (answer != null)
		{	
			String portalName = answer.getSender().getLocalName();
			String portalPassword = answer.getContent().toString();
			ACLMessage message = new ACLMessage(ACLMessage.REQUEST);
			message.setConversationId("portals");
			
			Position portalPosition = m_drone.m_knownPortalsPositions.get(portalName);
			int portalCapacity = m_drone.m_knowPortalsNbDronesAccepted.get(portalName);
			
			if (m_drone.m_fleet.size() < portalCapacity)
				return;
			
			JSONObject args = new JSONObject();
			args.put("action", Constants.Action.GO_TO.toString());
			args.put("name", portalName);
			args.put("password", portalPassword);
			args.put("position", portalPosition.toJson());
			args.put("nbDronesAccepted", portalCapacity);
			String JSONContent = args.toJSONString();
			
			message.setContent(JSONContent);
			
			Object[] arrayFleet = m_drone.m_fleet.keySet().toArray();
			
			for (int i=0; i<portalCapacity; i++)
			{
				AID droneAID = new AID("Drone" + arrayFleet[arrayFleet.length - i -1], AID.ISLOCALNAME);
				message.addReceiver(droneAID);
			}
			System.out.println("go to request sent by drone "+m_drone.m_id);
			if (m_drone.m_fleet.size() == portalCapacity) // the exact same number => the master has to go
			{
				System.out.println("the master is going");
				m_drone.m_state = Constants.State.TRAVELING_TO_PORTAL;
				System.out.println("drone "+m_drone.m_id+" is traveling");
				m_drone.m_destinationPortalName = portalName;
				m_drone.m_portalPassword = portalPassword;
				m_drone.m_goal = m_drone.m_knownPortalsPositions.get(portalName);
			}
			m_drone.send(message);
		} else
		{
			block();
		}
	}
	
	public boolean done()
	{
		return !m_drone.isActive();
	}
}

class ListenRejectPortalProposal extends Behaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public ListenRejectPortalProposal(Drone drone)
	{
		super(drone);
		m_drone = drone;
	}
	
	public void action()
	{
		ACLMessage answer = m_drone.receive(MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL));
		
		if (answer != null)
		{	
			String portalName = answer.getSender().getLocalName();
			// mettre à jour la file, pour choisir un autre portail
			m_drone.m_lastPortalProposal = System.currentTimeMillis();
		} else
		{
			block();
		}
	}
	
	public boolean done()
	{
		return !m_drone.isActive();
	}
}

// behaviour qui s'occupera de retirer un drone de la liste de flotte lorsqu'il meurt ou quitte la flotte
class ListenDroneLeaving extends Behaviour
{
	private static final long serialVersionUID = 1L;

	Drone m_drone;
	
	public ListenDroneLeaving(Drone drone)
	{
		super(drone);
		m_drone = drone;
	}
	
	public void action()
	{
		ACLMessage message = m_drone.receive(MessageTemplate.MatchPerformative(ACLMessage.FAILURE));
		
		if (message != null)
		{	
			Map<String, Object> parameters = Constants.fromJSONArray(message.getContent());
			int id = (int) parameters.get("id");
			System.out.println("suppressin du drone "+id+" de la flotte du drone "+m_drone.m_id);
			m_drone.m_fleet.remove(id);
		} else
		{
			block();
		}
	}
	
	public boolean done()
	{
		return !m_drone.isActive();
	}
}
