package jazmin.test.server.sip;

import java.io.IOException;

import jazmin.core.Jazmin;
import jazmin.log.Logger;
import jazmin.log.LoggerFactory;
import jazmin.misc.HexDump;
import jazmin.server.relay.RelayServer;
import jazmin.server.sip.SipContext;
import jazmin.server.sip.SipLocationBinding;
import jazmin.server.sip.SipMessageAdapter;
import jazmin.server.sip.SipSession;
import jazmin.server.sip.SipStatusCode;
import jazmin.server.sip.io.pkts.buffer.Buffer;
import jazmin.server.sip.io.pkts.buffer.Buffers;
import jazmin.server.sip.io.pkts.packet.sip.SipMessage;
import jazmin.server.sip.io.pkts.packet.sip.SipRequest;
import jazmin.server.sip.io.pkts.packet.sip.SipResponse;
import jazmin.server.sip.io.pkts.packet.sip.address.Address;
import jazmin.server.sip.io.pkts.packet.sip.address.SipURI;
import jazmin.server.sip.io.pkts.packet.sip.address.URI;
import jazmin.server.sip.io.pkts.packet.sip.header.ContactHeader;
import jazmin.server.sip.io.pkts.packet.sip.header.ContentLengthHeader;
import jazmin.server.sip.io.pkts.packet.sip.header.ExpiresHeader;
import jazmin.server.sip.io.pkts.packet.sip.header.ViaHeader;
import jazmin.server.sip.io.sdp.SessionDescription;
import jazmin.server.sip.io.sdp.SessionDescriptionParser;
import jazmin.server.sip.io.sdp.fields.ConnectionField;
import jazmin.server.sip.io.sdp.fields.MediaDescriptionField;

/**
 * 
 * @author yama
 *
 */
public class B2BUAMessageHandler extends SipMessageAdapter {
	private static Logger logger=LoggerFactory.get(B2BUAMessageHandler.class);

	
	
	public B2BUAMessageHandler(){
		
	}
	//
	@Override
	public void handleRequest(SipContext ctx, SipRequest message)
			throws Exception {
		if (message.isRegister()) {
			doRegister(ctx, message);
			dumpStore(ctx);
			return;
		}
		//
		if (message.isInvite()) {
			doInvite(ctx,message);
			return;
		}
		if(message.isAck()){
			if(ctx.getSession(false)!=null){
				ctx.getSession(false).invalidate();
			}
			return;
		}
		//
		ctx.getConnection().send(message.createResponse(SipStatusCode.SC_OK));
	}
	//
	@Override
	public void handleResponse(SipContext ctx, SipResponse response)
			throws Exception {
		SessionStatus ss=(SessionStatus) ctx.getSession().getUserObject();
		if(ss==null){
			return;
		}
		response.popViaHeader();
		//
		if(response.isInvite()&&response.hasContent()){
			RelayServer relayServer=Jazmin.getServer(RelayServer.class);
			//change sdp ip address and media port to relay server
			changeSDP(response, 
					relayServer.getHostAddresses().get(1),
					ss.audioRelayChannel.getLocalPeerPortB(),
					ss.videoRelayChannel.getLocalPeerPortB());
		}
		ss.connection.send(response);
	}
	//
	private void dumpStore(SipContext ctx){
		StringBuilder sb=new StringBuilder();
		ctx.getServer().getLocationBindings().forEach(b->{
			sb.append(b.getAor()+"\t->\t"+b);
			sb.append("\n");
		});
		logger.debug("\n"+sb);
	}
	//
	private void doInvite(SipContext ctx,SipRequest message)throws Exception{
		dumpStore(ctx);
		Address toAddress=message.getToHeader().getAddress();
		URI toURI=toAddress.getURI();
		SipLocationBinding toBinding=ctx.getServer().getLocationBinding((SipURI) toURI);
		if(toBinding==null){
			SipMessage notFoundMsg=message.createResponse(SipStatusCode.SC_NOT_FOUND);
			ctx.getConnection().send(notFoundMsg);
			return;
		}
		RelayServer relayServer=Jazmin.getServer(RelayServer.class);
		
		if(ctx.getSession(false)==null){
			SipSession session=ctx.getSession();
			SessionStatus ss=new SessionStatus();
			ss.originalRequest=message;
			ss.connection=ctx.getConnection();
			session.setUserObject(ss);
			//
			ss.audioRelayChannel=relayServer.createRelayChannel();
			ss.videoRelayChannel=relayServer.createRelayChannel();
		}
		//
		SessionStatus ss=(SessionStatus) ctx.getSession().getUserObject();
		changeSDP(message, 
				relayServer.getHostAddresses().get(0),
				ss.audioRelayChannel.getLocalPeerPortA(),
				ss.videoRelayChannel.getLocalPeerPortA());
		//
		ctx.getServer().proxyTo(toBinding.getConnection(),message);
	}
	//
	private void changeSDP(SipMessage message,String host,int audioPort,int videoPort)throws Exception{
		//if(true){
		//	return;
		//}
		//change sdp ip address and media port to relay server
		String sdp=new String(message.getRawContent().getArray(),"utf-8");
		System.err.println("old--------------------------------------");
		System.err.println(HexDump.dumpHexString(message.getRawContent().getArray()));
		System.err.println("old--------------------------------------");
		SessionDescription s=SessionDescriptionParser.parse(sdp);
		ConnectionField cf=s.getConnection();
		if(cf!=null){
			cf.setAddress(host);
		}
		ConnectionField audioConneion=s.getConnection("audio");
		if(audioConneion!=null){
			audioConneion.setAddress(host);
		}
		//
		s.getOrigin().setAddress(host);
		MediaDescriptionField audioField=s.getMediaDescription("audio");
		if(audioField!=null){
			audioField.setPort(audioPort);
		}
		MediaDescriptionField videoField=s.getMediaDescription("video");
		if(videoField!=null){
			videoField.setPort(videoPort);
		}
		byte newSdpBytes[]=s.toBytes();
		message.setRawContent(Buffers.wrap(newSdpBytes));
		ContentLengthHeader clh=message.getContentLengthHeader();
		System.err.println("new--------------------------------------");
		System.err.println(HexDump.dumpHexString(newSdpBytes));
		System.err.println("new--------------------------------------");
		clh.setContentLength(newSdpBytes.length);
	}
	//
	private void doRegister(SipContext ctx, SipRequest message)throws Exception{
		final SipResponse response = processRegisterRequest(ctx,message);
		ctx.getConnection().send(response);
	}
	//
	//
	/**
	 * Section 10.3 in RFC3261 outlines how to process a register request. For
	 * the purpose of this little exercise, we are skipping many steps just to
	 * keep things simple.
	 * 
	 * @param request
	 * @throws IOException
	 * @throws NumberFormatException
	 */
	private SipResponse processRegisterRequest(SipContext ctx,final SipRequest request)
			throws NumberFormatException, IOException {
		final SipURI aor = getAOR(request);
		final SipLocationBinding.Builder builder = SipLocationBinding.with();
		builder.aor(aor);
		builder.callId(request.getCallIDHeader());
		builder.expires(getExpires(request));
		builder.cseq(request.getCSeqHeader());
		builder.connection(ctx.getConnection());
		// NOTE: this is also cheating. There may be multiple contacts
		// and they must all get processed but whatever...
		SipURI newURI=SipURI.with().
				host(ctx.getConnection().getRemoteIpAddress()).
				port(ctx.getConnection().getRemotePort()).
				build();
		builder.contact(newURI);
		final SipLocationBinding binding = builder.build();
		ctx.getServer().updateLocationBinding(binding);
		final SipResponse response = request.createResponse(200);
		final SipURI contactURI = binding.getContact();
		contactURI.setParameter("expires", binding.getExpires());
		ViaHeader vh=response.getViaHeader();
		if(vh.hasRPort()){
			vh.setRPort(ctx.getConnection().getRemotePort());
			vh.setReceived(Buffers.wrap(ctx.getConnection().getRemoteIpAddress()));	
		}
		response.addHeader(ContactHeader.with(contactURI).build());
		return response;
	}

	//
	private int getExpires(final SipRequest request)
			throws NumberFormatException, IOException {
		final ContactHeader contact = request.getContactHeader();
		if (contact != null) {
			final Buffer value = contact.getParameter("expires");
			if (value != null) {
				return value.parseToInt();
			}
		}
		final ExpiresHeader expires = request.getExpiresHeader();
		return expires.getExpires();
	}
	/**
	 * The To-header contains the AOR (address-of-record) that the user wish to
	 * associate with the contact information in the Contact-header. We must
	 * also convert the To-header into its canonical form, which is the aor we
	 * will use as the key into the existing bindings.
	 * 
	 * @param request
	 * @return
	 */
	private SipURI getAOR(final SipRequest request) {
		final SipURI sipURI = (SipURI) request.getToHeader().getAddress().getURI();
		return SipURI.with().user(sipURI.getUser()).host(sipURI.getHost()).build();
	}

}