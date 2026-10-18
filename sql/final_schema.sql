/*M!999999\- enable the sandbox mode */ 

CREATE TABLE `app_meta` (
  `k` varchar(40) NOT NULL,
  `v` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`k`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `conflict_log` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `kind` varchar(20) NOT NULL,
  `message` varchar(300) NOT NULL,
  `created_at` timestamp NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `course` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `duration_years` int(11) NOT NULL DEFAULT 4,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `exam` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `subject_id` int(11) NOT NULL,
  `course_id` int(11) NOT NULL,
  `year` int(11) NOT NULL,
  `exam_date` date NOT NULL,
  `start_time` time NOT NULL,
  `end_time` time NOT NULL,
  PRIMARY KEY (`id`),
  KEY `subject_id` (`subject_id`),
  KEY `course_id` (`course_id`),
  CONSTRAINT `exam_ibfk_1` FOREIGN KEY (`subject_id`) REFERENCES `subject` (`id`) ON DELETE CASCADE,
  CONSTRAINT `exam_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `exam_seat` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `exam_id` int(11) NOT NULL,
  `room_id` int(11) NOT NULL,
  `seat_no` int(11) NOT NULL,
  `section_id` int(11) NOT NULL,
  `roll_no` varchar(40) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_exam_room_seat` (`exam_id`,`room_id`,`seat_no`),
  UNIQUE KEY `uq_exam_roll` (`exam_id`,`roll_no`),
  KEY `room_id` (`room_id`),
  KEY `section_id` (`section_id`),
  CONSTRAINT `exam_seat_ibfk_1` FOREIGN KEY (`exam_id`) REFERENCES `exam` (`id`) ON DELETE CASCADE,
  CONSTRAINT `exam_seat_ibfk_2` FOREIGN KEY (`room_id`) REFERENCES `room` (`id`) ON DELETE CASCADE,
  CONSTRAINT `exam_seat_ibfk_3` FOREIGN KEY (`section_id`) REFERENCES `section` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `faculty` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `faculty_unavailable` (
  `faculty_id` int(11) NOT NULL,
  `time_slot_id` int(11) NOT NULL,
  PRIMARY KEY (`faculty_id`,`time_slot_id`),
  KEY `time_slot_id` (`time_slot_id`),
  CONSTRAINT `faculty_unavailable_ibfk_1` FOREIGN KEY (`faculty_id`) REFERENCES `faculty` (`id`) ON DELETE CASCADE,
  CONSTRAINT `faculty_unavailable_ibfk_2` FOREIGN KEY (`time_slot_id`) REFERENCES `time_slot` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `move_request` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `entry_id` int(11) NOT NULL,
  `target_room_id` int(11) NOT NULL,
  `target_slot_id` int(11) NOT NULL,
  `status` enum('WAITING','GRANTED','CANCELLED') NOT NULL DEFAULT 'WAITING',
  `created_at` timestamp NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `entry_id` (`entry_id`),
  KEY `target_room_id` (`target_room_id`),
  KEY `target_slot_id` (`target_slot_id`),
  CONSTRAINT `move_request_ibfk_1` FOREIGN KEY (`entry_id`) REFERENCES `timetable_entry` (`id`) ON DELETE CASCADE,
  CONSTRAINT `move_request_ibfk_2` FOREIGN KEY (`target_room_id`) REFERENCES `room` (`id`) ON DELETE CASCADE,
  CONSTRAINT `move_request_ibfk_3` FOREIGN KEY (`target_slot_id`) REFERENCES `time_slot` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `notice` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `title` varchar(150) NOT NULL,
  `body` text NOT NULL,
  `audience` enum('all','faculty','student') NOT NULL DEFAULT 'all',
  `kind` enum('announcement','change') NOT NULL DEFAULT 'announcement',
  `created_by` varchar(60) NOT NULL DEFAULT 'system',
  `created_at` timestamp NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `room` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(20) NOT NULL,
  `type` enum('Lecture','Lab') NOT NULL,
  `capacity` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `section` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(30) NOT NULL,
  `course_id` int(11) DEFAULT NULL,
  `year` int(11) DEFAULT NULL,
  `strength` int(11) NOT NULL DEFAULT 60,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`),
  KEY `fk_section_course` (`course_id`),
  CONSTRAINT `fk_section_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `subject` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `faculty_id` int(11) NOT NULL,
  `is_lab` tinyint(1) NOT NULL DEFAULT 0,
  `course_id` int(11) DEFAULT NULL,
  `year` int(11) DEFAULT NULL,
  `periods_per_week` int(11) NOT NULL DEFAULT 3,
  PRIMARY KEY (`id`),
  KEY `fk_subject_faculty` (`faculty_id`),
  KEY `fk_subject_course` (`course_id`),
  CONSTRAINT `fk_subject_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_subject_faculty` FOREIGN KEY (`faculty_id`) REFERENCES `faculty` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `subject_faculty` (
  `subject_id` int(11) NOT NULL,
  `faculty_id` int(11) NOT NULL,
  PRIMARY KEY (`subject_id`,`faculty_id`),
  KEY `faculty_id` (`faculty_id`),
  CONSTRAINT `subject_faculty_ibfk_1` FOREIGN KEY (`subject_id`) REFERENCES `subject` (`id`) ON DELETE CASCADE,
  CONSTRAINT `subject_faculty_ibfk_2` FOREIGN KEY (`faculty_id`) REFERENCES `faculty` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `substitution` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `entry_id` int(11) NOT NULL,
  `sub_date` date NOT NULL,
  `substitute_faculty_id` int(11) NOT NULL,
  `reason` varchar(200) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_entry_date` (`entry_id`,`sub_date`),
  KEY `substitute_faculty_id` (`substitute_faculty_id`),
  CONSTRAINT `substitution_ibfk_1` FOREIGN KEY (`entry_id`) REFERENCES `timetable_entry` (`id`) ON DELETE CASCADE,
  CONSTRAINT `substitution_ibfk_2` FOREIGN KEY (`substitute_faculty_id`) REFERENCES `faculty` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `time_slot` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `day` enum('Mon','Tue','Wed','Thu','Fri') NOT NULL,
  `start_time` time NOT NULL,
  `end_time` time NOT NULL,
  `is_break` tinyint(1) NOT NULL DEFAULT 0,
  `label` varchar(40) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_day_time` (`day`,`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `timetable_entry` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `section_id` int(11) NOT NULL,
  `subject_id` int(11) NOT NULL,
  `faculty_id` int(11) NOT NULL,
  `room_id` int(11) NOT NULL,
  `time_slot_id` int(11) NOT NULL,
  `group_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_room_slot` (`room_id`,`time_slot_id`),
  UNIQUE KEY `uq_faculty_slot` (`faculty_id`,`time_slot_id`),
  UNIQUE KEY `uq_section_slot` (`section_id`,`time_slot_id`),
  KEY `subject_id` (`subject_id`),
  KEY `time_slot_id` (`time_slot_id`),
  CONSTRAINT `timetable_entry_ibfk_1` FOREIGN KEY (`section_id`) REFERENCES `section` (`id`) ON DELETE CASCADE,
  CONSTRAINT `timetable_entry_ibfk_2` FOREIGN KEY (`subject_id`) REFERENCES `subject` (`id`) ON DELETE CASCADE,
  CONSTRAINT `timetable_entry_ibfk_3` FOREIGN KEY (`faculty_id`) REFERENCES `faculty` (`id`) ON DELETE CASCADE,
  CONSTRAINT `timetable_entry_ibfk_4` FOREIGN KEY (`room_id`) REFERENCES `room` (`id`) ON DELETE CASCADE,
  CONSTRAINT `timetable_entry_ibfk_5` FOREIGN KEY (`time_slot_id`) REFERENCES `time_slot` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `users` (
  `id` varchar(20) NOT NULL,
  `name` varchar(100) NOT NULL,
  `password` varchar(100) NOT NULL DEFAULT 'changeme',
  `role` enum('admin','faculty','student') NOT NULL,
  `section_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;


