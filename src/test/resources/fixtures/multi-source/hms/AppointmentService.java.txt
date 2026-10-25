package com.example.HMS.service;

import com.example.HMS.anotations.Auditable;
import com.example.HMS.dto.AppointmentDto;
import com.example.HMS.dto.AppointmentMapper;
import com.example.HMS.entity.Appointment;
import com.example.HMS.entity.Doctor;
import com.example.HMS.entity.Patient;
import com.example.HMS.enums.AppointmentStatus;
import com.example.HMS.exception.AppointmentNotFoundException;
import com.example.HMS.exception.DoctorNotFoundException;
import com.example.HMS.exception.PatientNotFoundException;
import com.example.HMS.repository.AppointmentRepository;
import com.example.HMS.repository.DoctorRepository;
import com.example.HMS.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final AppointmentMapper appointmentMapper;

    // create appointment
    @Auditable(action = "CREATE_APPOINTMENT")
    public AppointmentDto createAppointment(AppointmentDto dto)
    {
        if (dto == null) {
            throw new IllegalArgumentException("Appointment data cannot be null");
        }

        Appointment appointmentEntity = appointmentMapper.toEntity(dto);

        Doctor doctor = doctorRepository.findById(dto.getDoctorId())
                .orElseThrow(() -> new DoctorNotFoundException("Doctor not found with id: " + dto.getDoctorId()));

        Patient patient = patientRepository.findById(dto.getPatientId())
                .orElseThrow(() -> new PatientNotFoundException("Patient not found with id: " + dto.getPatientId()));

        appointmentEntity.setDoctor(doctor);
        appointmentEntity.setPatient(patient);

        Appointment appointment = appointmentRepository.save(appointmentEntity);

        return appointmentMapper.toDto(appointment);
    }

    // get all appointments
    public Page<AppointmentDto> getAllAppointment(int page, int size)
    {
        Pageable pageable = PageRequest.of(page, size);
        Page<Appointment> appointments = appointmentRepository.findAll(pageable);

        return appointments.map(appointmentMapper::toDto);
    }

    // get appointment by id
    @Cacheable(value = "appointments", key = "#appointmentId")
    public AppointmentDto getAppointmentById(Long appointmentId)
    {
        Optional<Appointment> optionalAppointment = appointmentRepository.findById(appointmentId);

        if (optionalAppointment.isEmpty()){
            throw new AppointmentNotFoundException(String.format("Failed to find the appointment with id %d" , appointmentId));
        }

        Appointment appointment = optionalAppointment.get();

        // Force load lazy associations
        Hibernate.initialize(appointment.getDoctor());
        Hibernate.initialize(appointment.getPatient());

        return appointmentMapper.toDto(appointment);
    }

    // update appointment
    @Caching(
            put = {
                    @CachePut(value = "appointments", key = "#appointmentId")
            },
            evict = {
                    @CacheEvict(value = "appointmentsByDoctor", allEntries = true),
                    @CacheEvict(value = "appointmentsByPatient", allEntries = true)
            }
    )
    @Auditable(action = "UPDATE_APPOINTMENT")
    public AppointmentDto updateAppointmentById(Long appointmentId,AppointmentDto dto )
    {
        Optional<Appointment> optionalAppointment = appointmentRepository.findById(appointmentId);

        if (optionalAppointment.isEmpty()){
            throw new AppointmentNotFoundException(String.format("Failed to find the appointment with id %d" , appointmentId));
        }

        Appointment appointment = optionalAppointment.get();

        if (dto.getDoctorId() != null) {
            Doctor doctor = doctorRepository.findById(dto.getDoctorId())
                    .orElseThrow(() -> new DoctorNotFoundException(
                            "Doctor not found with id: " + dto.getDoctorId()));
            appointment.setDoctor(doctor);
        }

        if (dto.getPatientId() != null) {
            Patient patient = patientRepository.findById(dto.getPatientId())
                    .orElseThrow(() -> new PatientNotFoundException(
                            "Patient not found with id: " + dto.getPatientId()));
            appointment.setPatient(patient);
        }

        if (dto.getTime() != null) { appointment.setTime(dto.getTime()); }

        if (dto.getDate() != null) { appointment.setDate(dto.getDate()); }

        if (dto.getStatus() != null) { appointment.setStatus(dto.getStatus()); }

        if (dto.getEmail() != null) { appointment.setPatientEmail(dto.getEmail()); }

        Appointment savedAppointment = appointmentRepository.save(appointment);

        return appointmentMapper.toDto(savedAppointment);
    }

    // Reschedule appointment
    @Auditable(action = "RESCHEDULE_APPOINTMENT")
    public AppointmentDto rescheduleAppointment(Long appointmentId, AppointmentDto dto)
    {
        Optional<Appointment> optionalAppointment = appointmentRepository.findById(appointmentId);

        if (optionalAppointment.isEmpty()){
            throw new AppointmentNotFoundException(String.format("Failed to find the appointment with id %d" , appointmentId));
        }

        Appointment appointment = optionalAppointment.get();

        if (dto.getTime() != null){ appointment.setTime(dto.getTime()); }

        if (dto.getDate() != null){ appointment.setDate(dto.getDate()); }

        if (dto.getStatus() != null){ appointment.setStatus(AppointmentStatus.APPOINTMENT_RESCHEDULED); }

        Appointment savedAppointment =  appointmentRepository.save(appointment);

        return appointmentMapper.toDto(savedAppointment);
    }

    // cancel appointment
    @Caching(evict = {
            @CacheEvict(value = "appointments", key = "#appointmentId"),
            @CacheEvict(value = "appointmentsByDoctor", allEntries = true),
            @CacheEvict(value = "appointmentsByPatient", allEntries = true),
            @CacheEvict(value = "allAppointments", allEntries = true)
    })
    @Auditable(action = "CANCEL_APPOINTMENT")
    public Appointment cancelAppointment(Long appointmentId)
    {
        Optional<Appointment> optionalAppointment = appointmentRepository.findById(appointmentId);

        if (optionalAppointment.isEmpty()){
            throw new AppointmentNotFoundException(String.format("Failed to find the appointment with id %d" , appointmentId));
        }

        Appointment appointment = optionalAppointment.get();

        appointmentRepository.delete(appointment);

        return appointment;
    }

    // get appointments by date
    public List<AppointmentDto> getAppointmentsByDate(LocalDate date)
    {
        List<Appointment> appointment = appointmentRepository.findByDate(date);

        return appointment.stream().map(appointmentMapper::toDto).toList();
    }

    // get appointments by doctor
    @Cacheable(value = "appointmentsByDoctor", key = "#doctorId")
    public List<AppointmentDto> getAppointmentByDoctor(Long doctorId)
    {
        if (doctorId == null || doctorId <= 0) {
            throw new IllegalArgumentException("Doctor ID must be valid and positive");
        }

        List<Appointment> appointments =appointmentRepository.findByDoctorDoctorIdOrderByDateDesc(doctorId);

        return appointments.stream().map(appointmentMapper::toDto).toList();
    }

    // get appointments by patient
    @Cacheable(value = "appointmentsByPatient", key = "#patientId")
    public List<AppointmentDto> getAppointmentByPatient(Long patientId)
    {
        if (patientId == null || patientId <= 0) {
            throw new IllegalArgumentException("Patient ID must be valid and positive");
        }

        List<Appointment> appointment = appointmentRepository.findByPatientPatientId(patientId);

        return appointment.stream().map(appointmentMapper::toDto).toList();
    }

    // get appointments by status
    public List<AppointmentDto> getAppointmentByStatus(AppointmentStatus status)
    {
        if (status == null) {
            throw new IllegalArgumentException("Appointment Status can't be null");
        }

        List<Appointment> appointment = appointmentRepository.findByStatus(status);

        return appointment.stream().map(appointmentMapper::toDto).toList();
    }

}
