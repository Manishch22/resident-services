package io.mosip.registration.processor.packet.decryptor.job.tasklet;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.builder.CoreAuditRequestBuilder;
import io.mosip.registration.processor.core.code.AuditLogConstant;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.spi.filesystem.adapter.FileSystemAdapter;
import io.mosip.registration.processor.filesystem.ceph.adapter.impl.FilesystemCephAdapterImpl;
import io.mosip.registration.processor.packet.archiver.util.PacketArchiver;
import io.mosip.registration.processor.packet.archiver.util.exception.PacketNotFoundException;
import io.mosip.registration.processor.packet.archiver.util.exception.UnableToAccessPathException;
import io.mosip.registration.processor.packet.decryptor.job.Decryptor;
import io.mosip.registration.processor.packet.decryptor.job.exception.PacketDecryptionFailureException;
import io.mosip.registration.processor.packet.decryptor.job.messagesender.DecryptionMessageSender;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.exception.TablenotAccessibleException;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

/**
 * Tasklet class for Packet decryption job
 *
 * @author Jyoti Prakash Nayak
 *
 */
@Component
public class PacketDecryptorTasklet implements Tasklet {
	private static final Logger LOGGER = LoggerFactory.getLogger(PacketDecryptorTasklet.class);

	private static final String USER = "MOSIP_SYSTEM";

	private static final String LOGDISPLAY = "{} - {} - {}";

	@Autowired
	RegistrationStatusService<String, RegistrationStatusDto> registrationStatusService;

	private FileSystemAdapter<InputStream, Boolean> adapter = new FilesystemCephAdapterImpl();

	private DecryptionMessageSender decryptionMessageSender = new DecryptionMessageSender();

	@Autowired
	private Decryptor decryptor;

	@Autowired
	private PacketArchiver packetArchiver;

	private static final String DFS_NOT_ACCESSIBLE = "The DFS Path set by the System is not accessible";

	private static final String REGISTRATION_STATUS_TABLE_NOT_ACCESSIBLE = "The Registration Status table "
			+ "is not accessible";
	
	/** The core audit request builder. */
	@Autowired
	CoreAuditRequestBuilder coreAuditRequestBuilder;
	
	/** The event id. */
	private String eventId = "";
	
	/** The event name. */
	private String eventName = "";
	
	/** The event type. */
	private String eventType = "";
	
	/** The description. */
	private String description = "";

	/*
	 * (non-Javadoc)
	 *
	 * @see org.springframework.batch.core.step.tasklet.Tasklet#execute(org.
	 * springframework.batch.core.StepContribution,
	 * org.springframework.batch.core.scope.context.ChunkContext)
	 */
	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
		List<RegistrationStatusDto> dtolist = null;

		try {
			dtolist = registrationStatusService
					.getByStatus(RegistrationStatusCode.PACKET_UPLOADED_TO_FILESYSTEM.toString());
			eventId = EventId.RPR_401.toString();
			eventName = EventName.GET.toString();
			eventType = EventType.BUSINESS.toString();
			description = "Packet uploaded to file system";
			if (!(dtolist.isEmpty())) {
				dtolist.forEach(dto -> {
					try {

						decyptpacket(dto);

					} catch (TablenotAccessibleException e) {
						eventId = EventId.RPR_405.toString();
						eventName = EventName.EXCEPTION.toString();
						eventType = EventType.SYSTEM.toString();
						description = REGISTRATION_STATUS_TABLE_NOT_ACCESSIBLE;
						LOGGER.error(LOGDISPLAY, REGISTRATION_STATUS_TABLE_NOT_ACCESSIBLE, e.getMessage(), e);

					} catch (PacketDecryptionFailureException e) {

						LOGGER.error(LOGDISPLAY, e.getErrorCode(), e.getErrorText(), e);

						dto.setStatusCode(RegistrationStatusCode.PACKET_DECRYPTION_FAILED.toString());
						dto.setStatusComment("packet is in status packet for decryption failed");
						dto.setUpdatedBy(USER);
						registrationStatusService.updateRegistrationStatus(dto);
						eventId = EventId.RPR_405.toString();
						eventName = EventName.EXCEPTION.toString();
						eventType = EventType.SYSTEM.toString();
						description = "Packet decryption failed";
					} catch (IOException e) {
						eventId = EventId.RPR_405.toString();
						eventName = EventName.EXCEPTION.toString();
						eventType = EventType.SYSTEM.toString();
						description = DFS_NOT_ACCESSIBLE;
						LOGGER.error(LOGDISPLAY, DFS_NOT_ACCESSIBLE, e.getMessage(), e);

					}
				});
			} else if (dtolist.isEmpty()) {
				eventId = EventId.RPR_405.toString();
				eventName = EventName.EXCEPTION.toString();
				eventType = EventType.SYSTEM.toString();
				description = "There are currently no files to be decrypted";
				LOGGER.info("There are currently no files to be decrypted");
			}
		} catch (TablenotAccessibleException e) {
			eventId = EventId.RPR_405.toString();
			eventName = EventName.EXCEPTION.toString();
			eventType = EventType.SYSTEM.toString();
			description = REGISTRATION_STATUS_TABLE_NOT_ACCESSIBLE;
			LOGGER.error(LOGDISPLAY, REGISTRATION_STATUS_TABLE_NOT_ACCESSIBLE, e);
		}finally{		
			coreAuditRequestBuilder.createAuditRequestBuilder(description, eventId, eventName, eventType,
					AuditLogConstant.MULTIPLE_ID.toString());
		}
		return RepeatStatus.FINISHED;
	}

	/**
	 * method for decrypting registration packet
	 *
	 * @param dto
	 *            RegistrationStatus of the packet to be decrypted
	 * @throws IOException
	 * @throws PacketDecryptionFailureException
	 */
	private void decyptpacket(RegistrationStatusDto dto) throws IOException, PacketDecryptionFailureException {
		try {
			packetArchiver.archivePacket(dto.getRegistrationId());
		} catch (UnableToAccessPathException e) {
			LOGGER.error(LOGDISPLAY, e.getErrorCode(), e.getMessage(), e.getCause());
		} catch (PacketNotFoundException ex) {
			LOGGER.error(LOGDISPLAY, ex.getErrorCode(), ex.getMessage(), ex.getCause());
		}

		InputStream encryptedPacket = adapter.getPacket(dto.getRegistrationId());
		InputStream decryptedData = decryptor.decrypt(encryptedPacket, dto.getRegistrationId());

		if (decryptedData != null) {

			encryptedPacket.close();

			adapter.storePacket(dto.getRegistrationId(), decryptedData);

			adapter.unpackPacket(dto.getRegistrationId());

			dto.setStatusCode(RegistrationStatusCode.PACKET_DECRYPTION_SUCCESSFUL.toString());
			dto.setStatusComment("packet is in status packet for decryption successful");
			dto.setUpdatedBy(USER);
			registrationStatusService.updateRegistrationStatus(dto);

			MessageDTO messageDTO = new MessageDTO();

			messageDTO.setRid(dto.getRegistrationId());

			decryptionMessageSender.sendMessage(messageDTO);
			LOGGER.info(LOGDISPLAY, dto.getRegistrationId(),
					" Packet decrypted and extracted encrypted files stored in DFS.");

		} else {
			encryptedPacket.close();

			dto.setStatusCode(RegistrationStatusCode.PACKET_DECRYPTION_FAILED.toString());
			dto.setStatusComment("packet is in status packet for decryption failed");
			dto.setUpdatedBy(USER);
			registrationStatusService.updateRegistrationStatus(dto);

			LOGGER.info(LOGDISPLAY, dto.getRegistrationId(), " Packet is null and could not be  decrypted ");
		}

	}
}
