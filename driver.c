#include <ntddk.h>

#define IOCTL_READ_MEMORY  CTL_CODE(FILE_DEVICE_UNKNOWN, 0x800, METHOD_BUFFERED, FILE_ANY_ACCESS)
#define IOCTL_WRITE_MEMORY CTL_CODE(FILE_DEVICE_UNKNOWN, 0x801, METHOD_BUFFERED, FILE_ANY_ACCESS)

typedef struct _KERNEL_MEM_REQUEST {
    ULONG64 ProcessId;
    ULONG64 Address;
    ULONG64 Data;
    ULONG   Size;
} KERNEL_MEM_REQUEST, *PKERNEL_MEM_REQUEST;

NTSTATUS DriverIoControl(PDEVICE_OBJECT DeviceObject, PIRP Irp) {
    PIO_STACK_LOCATION stack = IoGetCurrentIrpStackLocation(Irp);
    ULONG code = stack->Parameters.DeviceIoControl.IoControlCode;
    PKERNEL_MEM_REQUEST req = (PKERNEL_MEM_REQUEST)Irp->AssociatedIrp.SystemBuffer;
    NTSTATUS status = STATUS_SUCCESS;
    PEPROCESS targetProcess = NULL;

    if (NT_SUCCESS(PsLookupProcessByProcessId((HANDLE)req->ProcessId, &targetProcess))) {
        SIZE_T bytes = 0;
        if (code == IOCTL_READ_MEMORY) {
            MmCopyVirtualMemory(targetProcess, (PVOID)req->Address,
                                PsGetCurrentProcess(), &req->Data,
                                req->Size, KernelMode, &bytes);
        } else if (code == IOCTL_WRITE_MEMORY) {
            MmCopyVirtualMemory(PsGetCurrentProcess(), &req->Data,
                                targetProcess, (PVOID)req->Address,
                                req->Size, KernelMode, &bytes);
        } else {
            status = STATUS_INVALID_DEVICE_REQUEST;
        }
        ObDereferenceObject(targetProcess);
    } else {
        status = STATUS_UNSUCCESSFUL;
    }

    Irp->IoStatus.Status = status;
    Irp->IoStatus.Information = sizeof(KERNEL_MEM_REQUEST);
    IoCompleteRequest(Irp, IO_NO_INCREMENT);
    return status;
}

void DriverUnload(PDRIVER_OBJECT DriverObject) {
    UNICODE_STRING symlink = RTL_CONSTANT_STRING(L"\\DosDevices\\CS2Kernel");
    IoDeleteSymbolicLink(&symlink);
    if (DriverObject->DeviceObject)
        IoDeleteDevice(DriverObject->DeviceObject);
}

NTSTATUS DriverEntry(PDRIVER_OBJECT DriverObject, PUNICODE_STRING RegistryPath) {
    UNREFERENCED_PARAMETER(RegistryPath);
    UNICODE_STRING devName = RTL_CONSTANT_STRING(L"\\Device\\CS2Kernel");
    UNICODE_STRING symLink = RTL_CONSTANT_STRING(L"\\DosDevices\\CS2Kernel");
    PDEVICE_OBJECT devObj = NULL;

    NTSTATUS status = IoCreateDevice(DriverObject, 0, &devName, FILE_DEVICE_UNKNOWN, 0, FALSE, &devObj);
    if (!NT_SUCCESS(status)) return status;

    status = IoCreateSymbolicLink(&symLink, &devName);
    if (!NT_SUCCESS(status)) {
        IoDeleteDevice(devObj);
        return status;
    }

    DriverObject->MajorFunction[IRP_MJ_DEVICE_CONTROL] = DriverIoControl;
    DriverObject->DriverUnload = DriverUnload;
    return STATUS_SUCCESS;
}
