use std::ffi::{c_char, c_short, c_uchar, c_void};

unsafe extern "C" {
    fn evs_rs_create() -> *mut c_void;
    fn evs_rs_missing(decoder: *mut c_void, out_frame: *mut c_short);
    fn evs_rs_decode(decoder: *mut c_void, frame: *mut c_uchar, frame_len: usize, out_frame: *mut c_short);
    fn evs_rs_free(decoder: *mut c_void);
    fn evs_es_create() -> *mut c_void;
    fn evs_es_encode(encoder: *mut c_void, frame: *mut c_short, out: *mut c_uchar) -> c_short;
    fn evs_es_free(encoder: *mut c_void);
}

pub struct EVSDecoder(*mut c_void);

unsafe impl Send for EVSDecoder {}
unsafe impl Sync for EVSDecoder {}

impl EVSDecoder {
    pub fn new() -> Self {
        Self(unsafe { evs_rs_create() })
    }

    pub fn decode(&mut self, frame: &mut [u8]) -> [i16; 640] {
        let mut result = [0i16; 640];
        unsafe { evs_rs_decode(self.0, frame.as_mut_ptr(), frame.len(), result.as_mut_ptr()) };
        result
    }

    pub fn missing(&mut self) -> [i16; 640] {
        let mut result = [0i16; 640];
        unsafe { evs_rs_missing(self.0, result.as_mut_ptr()) };
        result
    }
}

impl Drop for EVSDecoder {
    fn drop(&mut self) {
        unsafe { evs_rs_free(self.0) };
    }
}

pub struct EVSEncoder(*mut c_void);

unsafe impl Send for EVSEncoder {}
unsafe impl Sync for EVSEncoder {}

impl EVSEncoder {
    pub fn new() -> Self {
        Self(unsafe { evs_es_create() })
    }

    pub fn encode(&mut self, mut frame: [i16; 32000 / 50]) -> [u8; 61] {
        let mut result = [0u8; 61];
        let out = unsafe { evs_es_encode(self.0, frame.as_mut_ptr() as *mut c_short, result.as_mut_ptr()) };
        assert_eq!(out, result.len() as c_short);
        result
    }
}

impl Drop for EVSEncoder {
    fn drop(&mut self) {
        unsafe { evs_es_free(self.0) };
    }
}