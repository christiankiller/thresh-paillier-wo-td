package actors;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import math.LagrangianInterpolation;
import messages.Messages;
import messages.Messages.BGWResult;
import messages.Messages.Participants;
import protocol.BGWParameters.BGWPrivateParameters;
import protocol.BGWParameters.BGWPublicParameters;
import protocol.ProtocolParameters;
import actors.BGWProtocolActor.States;
import akka.actor.AbstractLoggingFSM;
import akka.actor.ActorRef;

public class BGWProtocolActor extends AbstractLoggingFSM<States, BGWData>{
	
	public enum States {INITILIZATION,
						BGW_AWAITING_PjQj, BGW_AWAITING_Ni};
	
	private final SecureRandom sr = new SecureRandom();
	private final ProtocolParameters protocolParameters;
	private final ActorRef master;
	
	public BGWProtocolActor(ProtocolParameters protocolParam) {
		this(protocolParam, null);
	}
	
	public BGWProtocolActor(ProtocolParameters protocolParam, ActorRef master) {
		this.protocolParameters = protocolParam;
		this.master = master != null ? master : self();
		
		startWith(States.INITILIZATION, BGWData.init());
		
		when(States.INITILIZATION, matchEvent(Participants.class,
				(participants,data) -> {
					Map<ActorRef,Integer> actors = participants.getParticipants();
					BGWPrivateParameters bgwPrivateParameters = BGWPrivateParameters.genFor(actors.get(this.master),
																							protocolParameters,
																							sr);
					BGWPublicParameters bgwSelfShare = BGWPublicParameters.genFor(actors.get(this.master),
																					bgwPrivateParameters,
																					sr);
					
					BGWData nextStateData = data.withPrivateParameters(bgwPrivateParameters)
										.withNewShare(bgwSelfShare, actors.get(this.master))
										.withParticipants(actors);

					actors.entrySet().stream()
						.filter(e -> !e.getKey().equals(this.master))
						.forEach(e -> e.getKey().tell(BGWPublicParameters.genFor(e.getValue(), bgwPrivateParameters, sr), this.master));
					
					return goTo(States.BGW_AWAITING_PjQj).using(nextStateData);
				}
				));
		
		
		when(States.BGW_AWAITING_PjQj, matchEvent(BGWPublicParameters.class, 
				(newShare, data) -> {
					Map<ActorRef,Integer> actors = data.getParticipants();
					BGWData dataWithNewShare = data.withNewShare(newShare, actors.get(sender()));
					if(!dataWithNewShare.hasShareOf(actors.values()))
						return stay().using(dataWithNewShare);
					else {
						Stream<Integer> badActors = dataWithNewShare.shares()
								.filter(e -> !e.getValue().isCorrect(protocolParameters, actors.get(this.master), e.getKey()))
								.map(e -> (Integer) e.getKey());
						if (badActors.count() > 0) {
							badActors.forEach(id -> broadCast(new Messages.Complaint(id),actors.keySet()));
							return stop().withStopReason(new Failure("A BGW share was invalid."));
						} else {
							BigInteger sumPj = dataWithNewShare.shares().map(e -> e.getValue().pj).reduce(BigInteger.ZERO, (p1,p2) -> p1.add(p2));
							BigInteger sumQj = dataWithNewShare.shares().map(e -> e.getValue().qj).reduce(BigInteger.ZERO, (q1,q2) -> q1.add(q2));
							BigInteger sumHj = dataWithNewShare.shares().map(e -> e.getValue().hj).reduce(BigInteger.ZERO, (h1,h2) -> h1.add(h2));
							BigInteger Ni = (sumPj.multiply(sumQj)).add(sumHj).mod(protocolParameters.Pp);
							broadCast(Ni, actors.keySet());
							return goTo(States.BGW_AWAITING_Ni).using(dataWithNewShare.withNewNi(Ni, actors.get(this.master)));
						}
					}
				}));
		
		
		when(States.BGW_AWAITING_Ni, matchEvent(BigInteger.class,
				(newNi,data) -> {
					Map<ActorRef,Integer> actors = data.getParticipants();
					BGWData dataWithNewNi = data.withNewNi(newNi, actors.get(sender()));
					if (!dataWithNewNi.hasNiOf(actors.values())){
						return stay().using(dataWithNewNi);
					}
					else {
						List<BigInteger> Nis = dataWithNewNi.nis()
								.map(e -> e.getValue())
								.collect(Collectors.toList());
						BigInteger N = LagrangianInterpolation.getIntercept(Nis, protocolParameters.Pp);
						if(this.master.equals(self()))
							return stop();
						else {
							this.master.tell(new BGWResult(N), self());
							return goTo(States.INITILIZATION).using(BGWData.init());
						}
					}
				}));
		
		onTransition((from,to) -> {
			System.out.println(String.format("%s transition %s -> %s", self().path(), from, to ));
		});
	}
	
	private void broadCast(Object o, Set<ActorRef> targets) {
		targets.stream().forEach(actor -> {if (!actor.equals(this.master)) actor.tell(o, this.master);});
	}
	
}
