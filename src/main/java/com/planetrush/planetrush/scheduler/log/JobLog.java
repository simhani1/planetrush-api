package com.planetrush.planetrush.scheduler.log;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
	name = "job_log",
	indexes = @Index(name = "idx_joblog_type_endtime", columnList = "job_type, end_time")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "job_type", nullable = false)
	private String jobType;

	@Column(name = "start_time", nullable = false)
	private LocalDateTime startTime;

	@Column(name = "end_time", nullable = false)
	private LocalDateTime endTime;

	@Column(name = "elapsed_time", length = 100)
	private String elapsedTime;

	public JobLog(String jobType) {
		this.jobType = jobType;
		this.startTime = LocalDateTime.now();
		this.endTime = null;
		this.elapsedTime = null;
	}

	public void finish() {
		this.endTime = LocalDateTime.now();
		this.elapsedTime = ChronoUnit.MILLIS.between(startTime, endTime) + "ms";
	}

}
