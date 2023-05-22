package io.mosip.tf.packet.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties
public class DeviceInfoDto {
	public String deviceInfo;
	public ErrorInfo error;
}
