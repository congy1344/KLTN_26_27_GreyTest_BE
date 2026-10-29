package com.hospital.patient.dto;

import com.hospital.patient.model.Patient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatientResponseDto {
    
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private LocalDate dateOfBirth;
    private Patient.Gender gender;
    private String address;
    private String bloodGroup;
    private String emergencyContact;
    private String medicalHistory;
    private String allergies;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public PatientResponseDto(Patient patient) {
        this.id = patient.getId();
        this.firstName = patient.getFirstName();
        this.lastName = patient.getLastName();
        this.email = patient.getEmail();
        this.phone = patient.getPhone();
        this.dateOfBirth = patient.getDateOfBirth();
        this.gender = patient.getGender();
        this.address = patient.getAddress();
        this.bloodGroup = patient.getBloodGroup();
        this.emergencyContact = patient.getEmergencyContact();
        this.medicalHistory = patient.getMedicalHistory();
        this.allergies = patient.getAllergies();
        this.createdAt = patient.getCreatedAt();
        this.updatedAt = patient.getUpdatedAt();
    }
}
